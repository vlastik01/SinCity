package sferencik.teamcity.sincity;

import jetbrains.buildServer.BuildProblemData;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.tests.TestInfo;
import jetbrains.buildServer.tests.TestName;
import jetbrains.buildServer.vcs.SVcsModification;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class SinCity {
    public static final String SINCITY_RANGE_TOP_BUILD_ID = "sincity.range.top.build.id";
    public static final String SINCITY_RANGE_TOP_BUILD_NUMBER = "sincity.range.top.build.number";
    public static final String SINCITY_RANGE_BOTTOM_BUILD_ID = "sincity.range.bottom.build.id";
    public static final String SINCITY_RANGE_BOTTOM_BUILD_NUMBER = "sincity.range.bottom.build.number";

    private final SRunningBuild newBuild;
    private final SFinishedBuild oldBuild;
    private final BuildCustomizerFactory buildCustomizerFactory;
    private final Map<String, String> sinCityParameters;

    public SinCity(SRunningBuild build,
                   BuildCustomizerFactory buildCustomizerFactory,
                   Map<String, String> sinCityParameters) {

        this.newBuild = build;
        this.buildCustomizerFactory = buildCustomizerFactory;
        this.sinCityParameters = sinCityParameters;

        this.oldBuild = newBuild.getPreviousFinished();
        Loggers.SERVER.debug("[SinCity] previous build: " + oldBuild);
    }

    /**
     * Tag the finishing build (if requested in the config) as either
     * 1) triggered by SinCity
     * 2) not triggered by SinCity
     */
    void tagBuild() {
        // tag the finished build
        SettingNames settingNames = new SettingNames();
        String tagParameterName = newBuild.getParametersProvider().get(SinCity.SINCITY_RANGE_TOP_BUILD_ID) == null
                ? settingNames.getTagNameForBuildsNotTriggeredBySinCity()
                : settingNames.getTagNameForBuildsTriggeredBySinCity();
        final String tagName = sinCityParameters.get(tagParameterName);

        if (tagName == null || tagName.isEmpty())
            return;

        Loggers.SERVER.debug("[SinCity] tagging build with '" + tagName + "'");
        final List<String> resultingTags = new ArrayList<String>(newBuild.getTags());
        resultingTags.add(tagName);
        newBuild.setTags(resultingTags);
    }

    /**
     * Investigate if culprit finding is needed. This is so if there are relevant failures and the finishing build has
     * covered  multiple changes.
     */
    void triggerCulpritFindingIfNeeded() {
        if (newBuild.getBuildStatus().isSuccessful()) {
            Loggers.SERVER.debug("[SinCity] the build succeeded; we're done.");
            return;
        }

        if (newBuild.getContainingChanges().size() <= 1) {
            Loggers.SERVER.debug("[SinCity] no intermediate changes found; we're done.");
            return;
        }

        if (getRelevantBuildProblems().isEmpty()
                && getRelevantTestFailures().isEmpty()) {
            Loggers.SERVER.debug("[SinCity] no relevant failures; we're done.");
            return;
        }

        Loggers.SERVER.info("[SinCity] will look for culprit");
        triggerCulpritFinding();
    }

    private List<BuildProblemData> getRelevantBuildProblems()
    {
        final String rbTriggerOnBuildProblem = sinCityParameters.get(new SettingNames().getRbTriggerOnBuildProblem());

        if (rbTriggerOnBuildProblem != null && rbTriggerOnBuildProblem.equals("No")) {
            Loggers.SERVER.debug("[SinCity] build problems do not trigger");
            return new ArrayList<BuildProblemData>();
        }

        final List<BuildProblemData> thisBuildProblems = newBuild.getFailureReasons();
        Loggers.SERVER.debug("[SinCity] this build's problems: " + thisBuildProblems);

        if (rbTriggerOnBuildProblem != null && rbTriggerOnBuildProblem.equals("All")) {
            Loggers.SERVER.debug("[SinCity] reporting all build problems");
            return thisBuildProblems;
        }

        final List<BuildProblemData> previousBuildProblems = oldBuild == null
                ? new ArrayList<BuildProblemData>()
                : oldBuild.getFailureReasons();
        Loggers.SERVER.debug("[SinCity] previous build's problems: " + previousBuildProblems);

        final List<BuildProblemData> newProblems = new ArrayList<BuildProblemData>(thisBuildProblems);
        newProblems.removeAll(previousBuildProblems);
        Loggers.SERVER.debug("[SinCity] new build problems: " + newProblems);
        Loggers.SERVER.debug("[SinCity] reporting new build problems");

        return newProblems;
    }

    private List<TestName> getRelevantTestFailures()
    {
        String rbTriggerOnTestFailure = sinCityParameters.get(new SettingNames().getRbTriggerOnTestFailure());

        if (rbTriggerOnTestFailure != null && rbTriggerOnTestFailure.equals("No")) {
            Loggers.SERVER.debug("[SinCity] test failures do not trigger");
            return new ArrayList<TestName>();
        }

        final List<TestName> thisBuildTestFailures = getTestNames(newBuild.getTestMessages(0, -1));
        Loggers.SERVER.debug("[SinCity] this build's test failures: " + thisBuildTestFailures);

        if (rbTriggerOnTestFailure != null && rbTriggerOnTestFailure.equals("All")) {
            Loggers.SERVER.debug("[SinCity] reporting all test failures");
            return thisBuildTestFailures;
        }

        final List<TestName> previousBuildTestFailures = oldBuild == null
                ? new ArrayList<TestName>()
                : getTestNames(oldBuild.getTestMessages(0, -1));
        Loggers.SERVER.debug("[SinCity] previous build's test failures: " + thisBuildTestFailures);

        final List<TestName> relevantTestFailures = new ArrayList<TestName>(thisBuildTestFailures);
        relevantTestFailures.removeAll(previousBuildTestFailures);
        Loggers.SERVER.debug("[SinCity] relevant test failures: " + relevantTestFailures);
        Loggers.SERVER.debug("[SinCity] reporting new test failures");

        return relevantTestFailures;
    }

    private List<TestName> getTestNames(List<TestInfo> tests)
    {
        List<TestName> testNames = new ArrayList<TestName>();
        for (TestInfo test : tests) {
            testNames.add(test.getTestName());
        }
        return testNames;
    }

    private void triggerCulpritFinding()
    {
        List<SVcsModification> suspectChanges = new ArrayList<SVcsModification>(newBuild.getContainingChanges());
        suspectChanges.remove(0);
        Collections.reverse(suspectChanges);

        BuildCustomizer buildCustomizer = getBuildCustomizer();

        for (SVcsModification change : suspectChanges) {
            Loggers.SERVER.info("[SinCity] Queueing change '" + change + "' having failed build " + newBuild);

            buildCustomizer.setChangesUpTo(change);

            buildCustomizer.createPromotion().addToQueue("SinCity, failures of " + newBuild.getBuildNumber());
        }
    }

    @NotNull
    private BuildCustomizer getBuildCustomizer() {
        BuildCustomizer buildCustomizer = buildCustomizerFactory.createBuildCustomizer(newBuild.getBuildType(), null);

        Map<String, String> parameters = new HashMap<String, String>();
        parameters.put(SinCity.SINCITY_RANGE_TOP_BUILD_ID, String.valueOf(newBuild.getBuildId()));
        parameters.put(SinCity.SINCITY_RANGE_TOP_BUILD_NUMBER, newBuild.getBuildNumber());
        parameters.put(SinCity.SINCITY_RANGE_BOTTOM_BUILD_ID, oldBuild == null
                ? "n/a"
                : String.valueOf(oldBuild.getBuildId()));
        parameters.put(SinCity.SINCITY_RANGE_BOTTOM_BUILD_NUMBER, oldBuild == null
                ? "n/a"
                : oldBuild.getBuildNumber());
        buildCustomizer.setParameters(parameters);

        return buildCustomizer;
    }
}
