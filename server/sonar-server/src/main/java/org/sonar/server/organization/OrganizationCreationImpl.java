/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.organization;

import java.util.Date;
import java.util.Optional;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.sonar.api.config.Settings;
import org.sonar.api.utils.System2;
import org.sonar.api.web.UserRole;
import org.sonar.core.config.CorePropertyDefinitions;
import org.sonar.core.permission.GlobalPermissions;
import org.sonar.core.util.UuidFactory;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.organization.DefaultTemplates;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.permission.GroupPermissionDto;
import org.sonar.db.permission.template.PermissionTemplateDto;
import org.sonar.db.user.GroupDto;
import org.sonar.db.user.UserDto;
import org.sonar.db.user.UserGroupDto;

import static com.google.common.base.Preconditions.checkState;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.sonar.server.organization.OrganizationCreation.NewOrganization.newOrganizationBuilder;

public class OrganizationCreationImpl implements OrganizationCreation {
  private final DbClient dbClient;
  private final System2 system2;
  private final UuidFactory uuidFactory;
  private final OrganizationValidation organizationValidation;
  private final Settings settings;

  public OrganizationCreationImpl(DbClient dbClient, System2 system2, UuidFactory uuidFactory,
    OrganizationValidation organizationValidation, Settings settings) {
    this.dbClient = dbClient;
    this.system2 = system2;
    this.uuidFactory = uuidFactory;
    this.organizationValidation = organizationValidation;
    this.settings = settings;
  }

  @Override
  public OrganizationDto create(DbSession dbSession, long creatorUserId, NewOrganization newOrganization) throws KeyConflictException {
    validate(newOrganization);
    String key = newOrganization.getKey();
    if (organizationKeyIsUsed(dbSession, key)) {
      throw new KeyConflictException(format("Organization key '%s' is already used", key));
    }

    OrganizationDto organization = insertOrganization(dbSession, newOrganization, dto -> {
    });
    GroupDto group = insertOwnersGroup(dbSession, organization);
    insertDefaultTemplate(dbSession, organization, group);
    addCurrentUserToGroup(dbSession, group, creatorUserId);

    dbSession.commit();

    return organization;
  }

  @Override
  public Optional<OrganizationDto> createForUser(DbSession dbSession, UserDto newUser) {
    if (!isCreatePersonalOrgEnabled()) {
      return Optional.empty();
    }

    NewOrganization newOrganization = newOrganizationBuilder()
      .setKey(organizationValidation.generateKeyFrom(newUser.getLogin()))
      .setName(toName(newUser.getLogin()))
      .build();
    checkState(!organizationKeyIsUsed(dbSession, newOrganization.getKey()),
      "Can't create organization with key '%s' for new user '%s' because an organization with this key already exists",
      newOrganization.getKey(),
      newUser.getLogin());

    OrganizationDto organization = insertOrganization(dbSession, newOrganization,
      dto -> dto.setGuarded(true).setUserId(newUser.getId()));
    GroupDto group = insertOwnersGroup(dbSession, organization);
    insertDefaultTemplate(dbSession, organization, group);
    addCurrentUserToGroup(dbSession, group, newUser.getId());

    dbSession.commit();

    return Optional.of(organization);
  }

  private String toName(String login) {
    String name = login.substring(0, Math.min(login.length(), OrganizationValidation.NAME_MAX_LENGTH));
    // should not happen has login can't be less than 2 chars, but we call it for safety
    organizationValidation.checkName(name);
    return name;
  }

  private boolean isCreatePersonalOrgEnabled() {
    return settings.getBoolean(CorePropertyDefinitions.ORGANIZATIONS_CREATE_PERSONAL_ORG);
  }

  private void validate(NewOrganization newOrganization) {
    requireNonNull(newOrganization, "newOrganization can't be null");
    organizationValidation.checkName(newOrganization.getName());
    organizationValidation.checkKey(newOrganization.getKey());
    organizationValidation.checkDescription(newOrganization.getDescription());
    organizationValidation.checkUrl(newOrganization.getUrl());
    organizationValidation.checkAvatar(newOrganization.getAvatar());
  }

  private OrganizationDto insertOrganization(DbSession dbSession, NewOrganization newOrganization, Consumer<OrganizationDto> extendCreation) {
    OrganizationDto res = new OrganizationDto()
      .setUuid(uuidFactory.create())
      .setName(newOrganization.getName())
      .setKey(newOrganization.getKey())
      .setDescription(newOrganization.getDescription())
      .setUrl(newOrganization.getUrl())
      .setAvatarUrl(newOrganization.getAvatar());
    extendCreation.accept(res);
    dbClient.organizationDao().insert(dbSession, res);
    return res;
  }

  private boolean organizationKeyIsUsed(DbSession dbSession, String key) {
    return dbClient.organizationDao().selectByKey(dbSession, key).isPresent();
  }

  private void insertDefaultTemplate(DbSession dbSession, OrganizationDto organizationDto, GroupDto group) {
    Date now = new Date(system2.now());
    PermissionTemplateDto permissionTemplateDto = dbClient.permissionTemplateDao().insert(
      dbSession,
      new PermissionTemplateDto()
        .setOrganizationUuid(organizationDto.getUuid())
        .setUuid(uuidFactory.create())
        .setName("Default template")
        .setDescription(format(PERM_TEMPLATE_DESCRIPTION_PATTERN, organizationDto.getName()))
        .setCreatedAt(now)
        .setUpdatedAt(now));

    insertGroupPermission(dbSession, permissionTemplateDto, UserRole.ADMIN, group);
    insertGroupPermission(dbSession, permissionTemplateDto, UserRole.ISSUE_ADMIN, group);
    insertGroupPermission(dbSession, permissionTemplateDto, UserRole.USER, null);
    insertGroupPermission(dbSession, permissionTemplateDto, UserRole.CODEVIEWER, null);

    dbClient.organizationDao().setDefaultTemplates(
      dbSession,
      organizationDto.getUuid(),
      new DefaultTemplates().setProjectUuid(permissionTemplateDto.getUuid()));
  }

  private void insertGroupPermission(DbSession dbSession, PermissionTemplateDto template, String permission, @Nullable GroupDto group) {
    dbClient.permissionTemplateDao().insertGroupPermission(dbSession, template.getId(), group == null ? null : group.getId(), permission);
  }

  /**
   * Owners group has an hard coded name, a description based on the organization's name and has all global permissions.
   */
  private GroupDto insertOwnersGroup(DbSession dbSession, OrganizationDto organization) {
    GroupDto group = dbClient.groupDao().insert(dbSession, new GroupDto()
      .setOrganizationUuid(organization.getUuid())
      .setName(OWNERS_GROUP_NAME)
      .setDescription(format(OWNERS_GROUP_DESCRIPTION_PATTERN, organization.getName())));
    GlobalPermissions.ALL.forEach(permission -> addPermissionToGroup(dbSession, group, permission));
    return group;
  }

  private void addPermissionToGroup(DbSession dbSession, GroupDto group, String permission) {
    dbClient.groupPermissionDao().insert(
      dbSession,
      new GroupPermissionDto()
        .setOrganizationUuid(group.getOrganizationUuid())
        .setGroupId(group.getId())
        .setRole(permission));
  }

  private void addCurrentUserToGroup(DbSession dbSession, GroupDto group, long createUserId) {
    dbClient.userGroupDao().insert(
      dbSession,
      new UserGroupDto().setGroupId(group.getId()).setUserId(createUserId));
  }
}
