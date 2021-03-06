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
package org.sonar.server.computation.task.projectanalysis.issue.filter;

import com.google.common.base.Splitter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.sonar.api.ce.ComputeEngineSide;
import org.sonar.api.config.Settings;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.core.issue.DefaultIssue;
import org.sonar.server.computation.task.projectanalysis.component.Component;
import org.sonar.server.computation.task.projectanalysis.component.SettingsRepository;
import org.sonar.server.computation.task.projectanalysis.component.TreeRootHolder;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.lang.String.format;
import static org.apache.commons.lang.StringUtils.defaultIfBlank;
import static org.sonar.core.config.IssueExclusionProperties.PATTERNS_MULTICRITERIA_EXCLUSION_KEY;
import static org.sonar.core.config.IssueExclusionProperties.PATTERNS_MULTICRITERIA_INCLUSION_KEY;
import static org.sonar.core.config.IssueExclusionProperties.RESOURCE_KEY;
import static org.sonar.core.config.IssueExclusionProperties.RULE_KEY;
import static org.sonar.server.computation.task.projectanalysis.component.Component.Type.FILE;

@ComputeEngineSide
public class IssueFilter {

  private static final Logger LOG = Loggers.get(IssueFilter.class);

  private final List<IssuePattern> exclusionPatterns;
  private final List<IssuePattern> inclusionPatterns;

  public IssueFilter(TreeRootHolder treeRootHolder, SettingsRepository settingsRepository) {
    Settings settings = settingsRepository.getSettings(treeRootHolder.getRoot());
    this.exclusionPatterns = loadPatterns(PATTERNS_MULTICRITERIA_EXCLUSION_KEY, settings);
    this.inclusionPatterns = loadPatterns(PATTERNS_MULTICRITERIA_INCLUSION_KEY, settings);
  }

  public boolean accept(DefaultIssue issue, Component component) {
    if (component.getType() != FILE || (exclusionPatterns.isEmpty() && inclusionPatterns.isEmpty())) {
      return true;
    }
    if (isExclude(issue, component)) {
      return false;
    }
    return isInclude(issue, component);
  }

  private boolean isExclude(DefaultIssue issue, Component component) {
    IssuePattern matchingPattern = null;
    Iterator<IssuePattern> patternIterator = exclusionPatterns.iterator();
    while (matchingPattern == null && patternIterator.hasNext()) {
      IssuePattern nextPattern = patternIterator.next();
      if (nextPattern.match(issue, component)) {
        matchingPattern = nextPattern;
      }
    }
    if (matchingPattern != null) {
      LOG.debug("Issue {} ignored by exclusion pattern {}", issue, matchingPattern);
      return true;
    }
    return false;
  }

  private boolean isInclude(DefaultIssue issue, Component component) {
    boolean atLeastOneRuleMatched = false;
    boolean atLeastOnePatternFullyMatched = false;
    IssuePattern matchingPattern = null;

    for (IssuePattern pattern : inclusionPatterns) {
      if (pattern.getRulePattern().match(issue.ruleKey().toString())) {
        atLeastOneRuleMatched = true;
        String componentPath = component.getReportAttributes().getPath();
        if (componentPath != null && pattern.getComponentPattern().match(componentPath)) {
          atLeastOnePatternFullyMatched = true;
          matchingPattern = pattern;
        }
      }
    }

    if (atLeastOneRuleMatched) {
      if (atLeastOnePatternFullyMatched) {
        LOG.debug("Issue {} enforced by pattern {}", issue, matchingPattern);
      }
      return atLeastOnePatternFullyMatched;
    } else {
      return true;
    }
  }

  private static List<IssuePattern> loadPatterns(String propertyKey, Settings settings) {
    List<IssuePattern> patterns = new ArrayList<>();
    String patternConf = defaultIfBlank(settings.getString(propertyKey), "");
    for (String id : Splitter.on(",").omitEmptyStrings().split(patternConf)) {
      String propPrefix = propertyKey + "." + id + ".";
      String componentPathPattern = settings.getString(propPrefix + RESOURCE_KEY);
      checkArgument(!isNullOrEmpty(componentPathPattern), format("File path pattern cannot be empty. Please check '%s' settings", propertyKey));
      String ruleKeyPattern = settings.getString(propPrefix + RULE_KEY);
      checkArgument(!isNullOrEmpty(ruleKeyPattern), format("Rule key pattern cannot be empty. Please check '%s' settings", propertyKey));
      patterns.add(new IssuePattern(componentPathPattern, ruleKeyPattern));
    }
    return patterns;
  }

}
