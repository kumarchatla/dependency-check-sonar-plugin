/*
 * Dependency-Check Plugin for SonarQube
 * Copyright (C) 2015-2017 Steve Springett
 * steve.springett@owasp.org
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
package org.sonar.dependencycheck;

import org.apache.commons.lang3.StringUtils;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.TextRange;
import org.sonar.api.batch.rule.Severity;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.issue.internal.DefaultIssueLocation;
import org.sonar.api.measures.Metric;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.scan.filesystem.PathResolver;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.api.utils.log.Profiler;
import org.sonar.dependencycheck.base.DependencyCheckConstants;
import org.sonar.dependencycheck.base.DependencyCheckMetrics;
import org.sonar.dependencycheck.base.DependencyCheckUtils;
import org.sonar.dependencycheck.parser.ReportParser;
import org.sonar.dependencycheck.parser.XmlReportFile;
import org.sonar.dependencycheck.parser.element.Analysis;
import org.sonar.dependencycheck.parser.element.Dependency;
import org.sonar.dependencycheck.parser.element.Vulnerability;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;

public class DependencyCheckSensor implements Sensor {

    private static final Logger LOGGER = Loggers.get(DependencyCheckSensor.class);
    private static final String SENSOR_NAME = "Dependency-Check";

    private final FileSystem fileSystem;
    private final PathResolver pathResolver;

    private int totalDependencies;
    private int vulnerableDependencies;
    private int vulnerabilityCount;
    private int criticalIssuesCount;
    private int majorIssuesCount;
    private int minorIssuesCount;

    public DependencyCheckSensor(FileSystem fileSystem, PathResolver pathResolver) {
        this.fileSystem = fileSystem;
        this.pathResolver = pathResolver;
    }

    private void addIssue(SensorContext context, InputFile reportFile, Dependency dependency, Vulnerability vulnerability) {

        TextRange artificialTextRange = reportFile.selectLine(vulnerability.getLineNumer());
        LOGGER.debug("TextRange: '{}' for dependency: '{}' and vulnerability: '{}'", artificialTextRange,
                dependency.getFileName(), vulnerability.getName());

        Severity severity = DependencyCheckUtils.cvssToSonarQubeSeverity(vulnerability.getCvssScore(), context.settings().getDouble(DependencyCheckConstants.SEVERITY_CRITICAL), context.settings().getDouble(DependencyCheckConstants.SEVERITY_MAJOR));

        context.newIssue()
                .forRule(RuleKey.of(DependencyCheckPlugin.REPOSITORY_KEY, DependencyCheckPlugin.RULE_KEY))
                .at(new DefaultIssueLocation()
                        .on(reportFile)
                        .at(artificialTextRange)
                        .message(formatDescription(dependency, vulnerability))
                )
                .overrideSeverity(severity)
                .save();

        incrementCount(severity);
    }

    /**
     * todo: Add Markdown formatting if and when Sonar supports it
     * https://jira.sonarsource.com/browse/SONAR-4161
     */
    private String formatDescription(Dependency dependency, Vulnerability vulnerability) {
        StringBuilder sb = new StringBuilder();
        sb.append("Filename: ").append(dependency.getFileName()).append(" | ");
        sb.append("Reference: ").append(vulnerability.getName()).append(" | ");
        sb.append("CVSS Score: ").append(vulnerability.getCvssScore()).append(" | ");
        if (StringUtils.isNotBlank(vulnerability.getCwe())) {
            sb.append("Category: ").append(vulnerability.getCwe()).append(" | ");
        }
        sb.append(vulnerability.getDescription());
        return sb.toString();
    }

    private void incrementCount(Severity severity) {
        switch (severity) {
            case CRITICAL:
                this.criticalIssuesCount++;
                break;
            case MAJOR:
                this.majorIssuesCount++;
                break;
            case MINOR:
                this.minorIssuesCount++;
                break;
            default:
                LOGGER.debug("Unknown severity {}", severity);
        }
    }

    private void addIssues(SensorContext context, Analysis analysis) {
        if (analysis.getDependencies() == null) {
            return;
        }
        for (Dependency dependency : analysis.getDependencies()) {
            LOGGER.debug("Processing dependency '{}', filePath: '{}'", dependency.getFileName(), dependency.getFilePath());
            InputFile testFile = fileSystem.inputFile(
                    fileSystem.predicates().hasPath(
                            escapeReservedPathChars(dependency.getFilePath())
                    )
            );

            String reportFilePath = context.settings().getString(DependencyCheckConstants.REPORT_PATH_PROPERTY);
            InputFile reportFile = fileSystem.inputFile(fileSystem.predicates().hasPath(reportFilePath));
            if (null == reportFile) {
                LOGGER.warn("skipping dependency '{}' as no inputFile could established.", dependency.getFileName());
                return;
            }

            int depVulnCount = dependency.getVulnerabilities().size();

            if (depVulnCount > 0) {
                vulnerableDependencies++;
                saveMetricOnFile(context, testFile, DependencyCheckMetrics.VULNERABLE_DEPENDENCIES, (double) depVulnCount);
            }
            saveMetricOnFile(context, testFile, DependencyCheckMetrics.TOTAL_VULNERABILITIES, (double) depVulnCount);
            saveMetricOnFile(context, testFile, DependencyCheckMetrics.TOTAL_DEPENDENCIES, (double) depVulnCount);

            for (Vulnerability vulnerability : dependency.getVulnerabilities()) {
                addIssue(context, reportFile, dependency, vulnerability);
                vulnerabilityCount++;
            }
        }
    }

    private void saveMetricOnFile(SensorContext context, InputFile inputFile, Metric<Serializable> metric, double value) {
        if (inputFile != null) {
            context.newMeasure().on(inputFile).forMetric(metric).withValue(value);
        }
    }

    private Analysis parseAnalysis(SensorContext context) throws IOException, ParserConfigurationException, SAXException {
        XmlReportFile report = new XmlReportFile(context.settings(), fileSystem, this.pathResolver);

        try (InputStream stream = report.getInputStream(DependencyCheckConstants.REPORT_PATH_PROPERTY)) {
        	return new ReportParser().parse(stream);
        }
    }

	private String getHtmlReport(SensorContext context) {
		XmlReportFile report = new XmlReportFile(context.settings(), fileSystem, this.pathResolver);
		File reportFile = report.getFile(DependencyCheckConstants.HTML_REPORT_PATH_PROPERTY);
		if (reportFile == null || !reportFile.exists() || !reportFile.isFile() || !reportFile.canRead()) {
			return null;
		}
		int len = (int) reportFile.length();
		try (FileInputStream reportFileInputStream = new FileInputStream(reportFile)) {
			byte[] readBuffer = new byte[len];
			reportFileInputStream.read(readBuffer, 0, len);
			return new String(readBuffer);
		} catch (IOException e) {
			LOGGER.error("", e);
			return null;
		}
	}

    private void saveMeasures(SensorContext context) {
        context.newMeasure().forMetric(DependencyCheckMetrics.HIGH_SEVERITY_VULNS).on(context.module()).withValue(criticalIssuesCount).save();
        context.newMeasure().forMetric(DependencyCheckMetrics.MEDIUM_SEVERITY_VULNS).on(context.module()).withValue(majorIssuesCount).save();
        context.newMeasure().forMetric(DependencyCheckMetrics.LOW_SEVERITY_VULNS).on(context.module()).withValue(minorIssuesCount).save();
        context.newMeasure().forMetric(DependencyCheckMetrics.TOTAL_DEPENDENCIES).on(context.module()).withValue(totalDependencies).save();
        context.newMeasure().forMetric(DependencyCheckMetrics.VULNERABLE_DEPENDENCIES).on(context.module()).withValue(vulnerableDependencies).save();
        context.newMeasure().forMetric(DependencyCheckMetrics.TOTAL_VULNERABILITIES).on(context.module()).withValue(vulnerabilityCount).save();

        context.newMeasure().forMetric(DependencyCheckMetrics.INHERITED_RISK_SCORE).on(context.module()).withValue(DependencyCheckMetrics.inheritedRiskScore(criticalIssuesCount, majorIssuesCount, minorIssuesCount)).save();
        context.newMeasure().forMetric(DependencyCheckMetrics.VULNERABLE_COMPONENT_RATIO).on(context.module()).withValue(DependencyCheckMetrics.vulnerableComponentRatio(vulnerabilityCount, vulnerableDependencies)).save();

        String htmlReport = getHtmlReport(context);
        if (htmlReport != null) {
            context.newMeasure().forMetric(DependencyCheckMetrics.REPORT).on(context.module()).withValue(htmlReport).save();
        }
    }

    @Override
    public String toString() {
        return SENSOR_NAME;
    }

    @Override
    public void describe(SensorDescriptor sensorDescriptor) {
        sensorDescriptor.name(SENSOR_NAME);
    }

    @Override
    public void execute(SensorContext sensorContext) {
        Profiler profiler = Profiler.create(LOGGER);
        profiler.startInfo("Process Dependency-Check report");
        try {
            Analysis analysis = parseAnalysis(sensorContext);
            this.totalDependencies = analysis.getDependencies().size();
            addIssues(sensorContext, analysis);
        } catch (FileNotFoundException e) {
            LOGGER.debug("Analysis aborted due to missing report file", e);
        } catch (Exception e) {
            throw new RuntimeException("Can not process Dependency-Check report.", e);
        } finally {
            profiler.stopInfo();
        }
        saveMeasures(sensorContext);
    }

    /**
     * The following characters are reserved on Windows systems.
     * Some are also reserved on Unix systems.
     *
     * < (less than)
     * > (greater than)
     * : (colon)
     * " (double quote)
     * / (forward slash)
     * \ (backslash)
     * | (vertical bar or pipe)
     * ? (question mark)
     * (asterisk)
     */
    private String escapeReservedPathChars(String path) {
        /*
        todo:
        For the time being, only try to replace ? (question mark) since that
        is the only reserved character intentionally used by Dependency-Check.
         */
        String replacement = path.contains("/") ? "/" : "\\";
        return path.replace("?", replacement);
    }
}
