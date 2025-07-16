/*
sonarQubeScanTest()

[ Desc ]
Runs the test project(s) which are part of the solution & submits a SonarQube (SQ) scan.
Fails the stage if some tests failed – only after the SQ scan was submitted.
(Re)creates a NuGet.Config file for the execution using as sources [creditrisk-nuget-dev] and [nuget-remote] repositories.

Please note the sonarscanner tool usage has 2 steps (for .NET projects), one run before and after the test run,
so it's a perfect combination to run tests and SQ together in this function.

Please add SonarQube.Analysis.xml file for repository file exclusion.

[ Params ]
sonarProjectKey
    - name for the project as to appear on the SQ portal
    - (the branches are differentiated under the project automatically)
sonarInstallationName
    - installation name
    - ** has a def. value ** – as configured in the current CBJ template (v1.30)
sonarCodeDuplicationExclusionFilter
sonarCodeDuplicationExclusionFilter
    - Filter for exclusion of files to not be marked as duplicated code
    - Value passed to the /d:sonar.cpd.exclusions parameter at SonarQube startup
    - ** Has a default value **: an empty string (i.e., no exclusion applied)

sonarCodeDuplicationExclusionJustificationAndApprovers
    - Justification for applying the exclusion – including lead engineer(s), reason, and approval date
    - Will be printed in the pipeline
    - This value is mandatory if [sonarCodeDuplicationExclusionFilter] is set; otherwise N/A
    - Minimum length requirement: 200 characters

testExecutionFilter
    - Filter to choose a subset of tests to run
    - Passed to the `--filter` parameter during `dotnet test`
    - Use only in exceptional cases. Normally, all tests should be executed
    - ** Has a default value **: an empty string (executes all tests)
    - Sample: 'Category=ScenarioA'
      Full doc: https://learn.microsoft.com/en-us/dotnet/core/testing/selective-unit-tests?pivots=xunit

testCoverageExclude
    - Filter for 'coverage inclusion' used by Coverlet
    - Passed to the `/p:Exclude` parameter during `dotnet test`
    - ** Has a default value **: an empty string (includes all classes)
    - Sample: [NBS.CR.ProjectName.CsProjName.NamespaceA]* 
      Full doc: https://github.com/coverlet-coverage/coverlet/blob/master/Documentation/MSBuildIntegration.md#filters

testTargetFramework
    - Filter for running tests only on a specific target framework (others are skipped)
    - Mapped to `.NET` framework using `--framework` parameter during `dotnet test`
    - Helps in multi-target builds where the SDK for one version might be missing in the CBJ pod
    - ** Has a default value **: an empty string (i.e., tests run for all target frameworks)
/*
    - supported values: '.NET6', '.NET8'

multipleTestProjectsFlag
    - Flag to handle merging of test coverage files across multiple test projects
    - Note: this enforces sequential test execution in the background, so it may take longer
    - ** Has a default value ** - '0'
    - Supported values: '0', '1'
*/

import raas.nuget.NugetConfig
import raas.nuget.NugetConfigBinaryRepo
import raas.binaryrepo.BinaryRepoConfig

void call(Map config) {

    supportedTargetFrameworks = ['.NET6', '.NET8']
    dotnetTargetFrameworkMap = ['.NET6': 'net6.0', '.NET8': 'net8.0']

    safeParams = config ?: [:]

    sonarProjectKey = safeParams['sonarProjectKey'] ?: ''
    installationName = safeParams['sonarInstallationName'] ?: 'sonarqube'
    sonarCodeDuplicationExclusionFilter = safeParams['sonarCodeDuplicationExclusionFilter'] ?: ''
    sonarCodeDuplicationExclusionJustificationAndApprovers = safeParams['sonarCodeDuplicationExclusionJustificationAndApprovers'] ?: ''
    testExecutionFilter = safeParams['testExecutionFilter'] ?: ''
    testCoverageExclude = safeParams['testCoverageExclude'] ?: ''
    testTargetFramework = safeParams['testTargetFramework'] ?: ''
multipleTestProjectsFlag = safeParams['multipleTestProjectsFlag'] ?: '0'

dotnetTestTargetFramework = ''
if (testTargetFramework != '') {
    if (!supportedTargetFrameworks.contains(testTargetFramework)) {
        error "Provided testTargetFramework value '${testTargetFramework}' is not supported. Supported list: '${supportedTargetFrameworks}'"
    }
    dotnetTestTargetFramework = "--framework ${dotnetTargetFrameworkMap[testTargetFramework]}"
}

if (sonarProjectKey == '') {
    error 'sonarProjectKey is required.'
}

if (sonarCodeDuplicationExclusionFilter != '') {
    if (sonarCodeDuplicationExclusionJustificationAndApprovers == '') {
        error 'sonarCodeDuplicationExclusionJustificationAndApprovers is required when sonarCodeDuplicationExclusionFilter is set.'
    }
    if (sonarCodeDuplicationExclusionJustificationAndApprovers.length() < 200) {
        error 'sonarCodeDuplicationExclusionJustificationAndApprovers value has to be at least 200-characters long.'
    }
}

dotnetTestArgs = '/p:CoverletOutputFormat=opencover'
if (multipleTestProjectsFlag == '1') {
    coverageFileName = 'coverage.json'
    if (testTargetFramework == '.NET8') {
        coverageFileName = "coverage.${dotnetTargetFrameworkMap[testTargetFramework]}.json"
    }
    dotnetTestArgs = '-m:1 /p:CoverletOutputFormat=\"opencover,json\" /p:MergeWith=PWD-HERE/artifacts/coverage/' + coverageFileName
    echo "Multiple test projects handling enabled."
}

sonarScannerMsBuildPath = '/home/dockeruser/.dotnet/tools'
sonarScannerToolVersion = '6.2.0' // latest version as of today, requires minimum Java 17

testRunLabelPostfix = ''
if (testExecutionFilter != '') {
    testRunLabelPostfix = "[${testExecutionFilter}]"
}

if (dotnetTestTargetFramework != '') {
    testRunLabelPostfix += " for '${dotnetTargetFrameworkMap[testTargetFramework]}' framework"
}

if (testCoverageExclude != '') {
    testRunLabelPostfix += " with coverage excluding '${testCoverageExclude}'"
}
if (sonarCodeDuplicationExclusionFilter != '') {
    testRunLabelPostfix += " (with SQ code duplication excluding '${sonarCodeDuplicationExclusionFilter}')"
    echo "WARNING: Sonarqube configured to exclude following files from code duplication: ${sonarCodeDuplicationExclusionFilter}. This is only permitted with the Lead Engineer's approval."
    echo "WARNING: Following justification provided: [${sonarCodeDuplicationExclusionJustificationAndApprovers}]"
}

username = 'svccbjintcreditrisk'
artifactoryCredentialsId = 'svccbjintcreditrisk'
usernameIdDev = BinaryRepoConfig.BINARY_REPO_PULL_USERNAME_ID
credentialsIdDev = BinaryRepoConfig.BINARY_REPO_PULL_TOKEN_ID

NugetConfigBinaryRepo obj = null
// NugetConfig obj = null;

withSonarQubeEnv(installationName) {
    withEnv([
        "SONAR_PROJECT_NAME=${sonarProjectKey}",
        "SONAR_AUTH_TOKEN=${SONAR_AUTH_TOKEN}",
        "SONAR_HOST_URL=${SONAR_HOST_URL}",
        "SQ_SCANNER_TOOL_VERSION_NO=${sonarScannerToolVersion}",
        "SONAR_SCANNER_MSBUIlD_PATH=${sonarScannerMsBuildPath}",
        "SONAR_CODE_DUP_EXCL_FILTER=${sonarCodeDuplicationExclusionFilter}",
        "DOTNET_TEST_ARGS=${dotnetTestArgs}",
        "TEST_EXEC_FILTER=${testExecutionFilter}",
        "TEST_COVER_EXCLUDE=${testCoverageExclude}",
        "ARTIFACTORY_USER=${usernameIdDev}",
        "ARTIFACTORY_CRED_ID=${credentialsIdDev}"
    ]) {
        withCredentials([
            string(credentialsId: credentialsIdDev, variable: 'ARTIFACTORY_TOKEN'),
            string(credentialsId: usernameIdDev, variable: 'ARTIFACTORY_USER_NAME')
        ]) {
            // obj = new NugetConfig(this, ARTIFACTORY_USER, ARTIFACTORY_TOKEN)
            obj = new NugetConfigBinaryRepo(this, ARTIFACTORY_USER_NAME, ARTIFACTORY_TOKEN,
                    BinaryRepoConfig.BINARY_REPO_URL, BinaryRepoConfig.DEV_NUGET_REPO)
            obj.injectNugetConfigWithMultiSources()
        }
testRunStatus = sh label: "Test run $testRunLabelPostfix", returnStatus: true, script: '''
#!/bin/bash

# [0] - success
# [1] - build failed
# [2] - test run failed
# [3] - sonarscanner setup/start failed

set +x  # disable command printing
set -e  # disable failing on exception

# Install the SonarQube scanner for .NET
dotnet tool install dotnet-sonarscanner -v normal --tool-path $SONAR_SCANNER_MSBUIlD_PATH --ignore-failed-sources --version $SQ_SCANNER_TOOL_VERSION_NO

export PATH="$PATH:$SONAR_SCANNER_MSBUIlD_PATH"
export DOTNET_CLI_TELEMETRY_OPTOUT=1
chmod -R +x $SONAR_SCANNER_MSBUIlD_PATH

# Begin monitoring the build & test
if [ "$SONAR_CODE_DUP_EXCL_FILTER" != '' ]; then
    # /d:sonar.cpd.exclusions="" causes error
    dotnet sonarscanner begin /key:$SONAR_PROJECT_NAME /d:sonar.host.url="$SONAR_HOST_URL" \
        /d:sonar.login=$SONAR_AUTH_TOKEN /d:sonar.cs.opencover.reportsPaths="artifacts/coverage/*.xml" \
        /d:sonar.cs.vstest.reportsPaths="artifacts/TestResults/*.trx" \
        /d:sonar.cpd.exclusions="$SONAR_CODE_DUP_EXCL_FILTER"
else
    dotnet sonarscanner begin /key:$SONAR_PROJECT_NAME /d:sonar.host.url="$SONAR_HOST_URL" \
        /d:sonar.login=$SONAR_AUTH_TOKEN /d:sonar.cs.opencover.reportsPaths="artifacts/coverage/*.xml" \
        /d:sonar.cs.vstest.reportsPaths="artifacts/TestResults/*.trx"
fi

if [ $? -ne 0 ]; then
    echo "Sonarscanner setup failed."
    exit 3
fi

# Running build separately as SQ complains when 'dotnet test /p:Coll...' runs
# and no test project found that we didn't build (even though the command did that)
dotnet build

if [ $? -ne 0 ]; then
    echo "Solution build failed."
    exit 1
fi

# $PWD needed in CoverletOutput as the coverage is executed from the project directory!
DOTNET_TEST_ARGS=$(echo $DOTNET_TEST_ARGS | sed "s|PWD-HERE|$PWD|")

dotnet test $DOTNET_TEST_TARGET_FW --no-build --filter "$TEST_EXEC_FILTER" /p:CollectCoverage=true $DOTNET_TEST_ARGS \
    /p:CoverletOutput="$PWD/artifacts/coverage/" /p:Exclude="$TEST_COVER_EXCLUDE"

if [ $? -ne 0 ]; then
echo "Test run failed – some tests failed or the run failed overall."
    exit 2
fi

exit 0
'''

echo "deleting nuget file"
obj.deleteNugetConfig()

if (testRunStatus == 1) {
    error 'Solution build failed.' // this avoids the actual error hidden by the later 'dotnet sonarscanner end' failure
}

sh label: 'SonarQube scan submission', returnStatus: false, script: '''
#!/bin/bash

set +x  # disable command printing

export PATH="$PATH:$SONAR_SCANNER_MSBUIlD_PATH"
chmod -R +x $SONAR_SCANNER_MSBUIlD_PATH

# End monitoring and submit results to SonarQube
dotnet sonarscanner end /d:sonar.login=$SONAR_AUTH_TOKEN
'''
if (testRunStatus == 0) {
    // success
    // This is to allow Sonar analysis to complete before sonarqualitygate tries to fetch the result
    // Seems like webhook is missing and with webhook, delay is not required
    sleep(30)
} else if (testRunStatus == 2) {
    error 'Some tests failed.'
} else if (testRunStatus == 3) {
    error 'Sonarscanner setup/start failed.'
} else {
    error 'Unexpected issue at the script run'
}
}
}
}
