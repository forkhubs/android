// this is called from ${project.root}/Jenkinsfile
// this pipeline to build feature branches (initially)

def execute() {

    def checks
    def gitStatus
    def common
    def keys
    def bupa
    def gitBranch

    releaseKeys = [
            [$class: 'StringBinding', credentialsId: 'ANDROID_PLAYSTORE_UK_STORE_PASS', variable: 'RELEASE_STORE_PASS'],
            [$class: 'StringBinding', credentialsId: 'ANDROID_PLAYSTORE_UK_KEY_PASS', variable: 'RELEASE_KEY_PASS'],
            [$class: 'StringBinding', credentialsId: 'ANDROID_PLAYSTORE_UK_KEY_ALIAS', variable: 'RELEASE_KEY_ALIAS'],
            [$class: 'FileBinding', credentialsId: 'ANDROID_PLAYSTORE_UK_KEYSTORE', variable: 'RELEASE_KEYSTORE_LOCATION']
    ]

    nhsKeys = [
            [$class: 'StringBinding', credentialsId: 'ANDROID_PLAYSTORE_NHS_STORE_PASS', variable: 'RELEASE_STORE_PASS'],
            [$class: 'StringBinding', credentialsId: 'ANDROID_PLAYSTORE_NHS_KEY_PASS', variable: 'RELEASE_KEY_PASS'],
            [$class: 'StringBinding', credentialsId: 'ANDROID_PLAYSTORE_NHS_KEY_ALIAS', variable: 'RELEASE_KEY_ALIAS'],
            [$class: 'FileBinding', credentialsId: 'ANDROID_PLAYSTORE_NHS_KEYSTORE', variable: 'RELEASE_KEYSTORE_LOCATION']
    ]

    node('android') {

        unstash 'sources'
        gitBranch = sh(returnStdout: true, script: 'git rev-parse --abbrev-ref HEAD').trim()
        gitStatus = load 'scripts/jenkins/lib/git-status.groovy'
        checks = load 'scripts/jenkins/steps/checks.groovy'
        common = load 'scripts/jenkins/lib/common.groovy'
        keys = load 'scripts/jenkins/lib/keys.groovy'
        bupa = load 'scripts/jenkins/steps/bupa.groovy'
        hockey = load 'scripts/jenkins/steps/hockey.groovy'

        step([$class: 'WsCleanup', notFailBuild: true])
    }

    common.config.fastDexguardBuilds = true

    parallel(
            'Unit tests': {
                checks.unitTests(false)
            },
            'Lint': {
                checks.lint()
            },
            'Checkstyle': {
                checks.checkstyle()
            }
    )

    milestone(label: 'Finished testing!')
    checks.publishReports()
    echo "Job result : ${currentBuild.result}"

    def uniqueBuildNumber = common.buildCounter().number
    env.BUILD_COUNTER = uniqueBuildNumber
    echo ">>> BUILD_COUNTER = ${env.BUILD_COUNTER} <<< "


    def gradleArgs = common.gradleParametersWithVersion()
    stage('Package') {
        switch (env.BRANCH_NAME) {
            case ~/^release\/.*/: hockey.release(releaseKeys, nhsKeys, gradleArgs); break;
            case ~/^(v2|develop)/: hockey.develop( gradleArgs); break;
            case ~/^(PR.*|feature\/.*)/: hockey.pullRequest(gradleArgs); break;
            default: error("Branch name is not right for pushing APKs to hockey!");
        }
        echo "Job result : ${currentBuild.result}"
        milestone(label: 'Finished packaging!')
    }

    echo "Job result : ${currentBuild.result}"
    milestone(label: 'Finished packaging!')

    stage('Finish') {
        node('android') {
            unstash 'pipeline'
            echo "Job result : ${currentBuild.result}"
            common.reportFinalBuildStatus()
            common.slackFeed(currentBuild.result)
        }
    }
    stage('QA Approval') {
        node('android') {
            try {
                userInput = input(
                        id: 'Proceed1', message: 'Approved by QA Engineer ?', parameters: [
                        [$class: 'BooleanParameterDefinition', defaultValue: false, description: 'QA Approval', name: 'Please approve this Pull Request']
                ]
                )
            } catch (err) {
                def user = err.getCauses()[0].getUser()
            }
        }

        if (userInput == true) {
            println ">>> User input: true <<<"
            gitStatus.reportGitStatus('QA Approval', 'QA Approval', 'SUCCESS')
        } else {
            println ">>> User input: da <<<"
            gitStatus.reportGitStatus('QA Approval', 'QA Approval', 'FAILURE')
        }
    }
}

return this
