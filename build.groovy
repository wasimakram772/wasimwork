@Library('common-build-library') _
pipeline {

    agent any

    tools {
        jdk 'JDK8'
        maven 'Maven3.5.0'
    }

    parameters {
        booleanParam(name: 'DEPLOY_TO_CT', defaultValue: false, description: 'Deploy to Control Tower. False by default.')
        booleanParam(name: 'RELEASE', defaultValue: false, description: 'Release bundle and deploy it to nexus. Bundle will be deployed to nexus. False by default.')
        string(name: 'SLACK_CHANNEL', defaultValue: '', description: 'Slack channel for sending notifications about build status. IF empty - no slack notifications will be sent')
        string(name: 'EMAILS', defaultValue: '', description: 'Comma separated list of emails for sending notifications about build status. IF empty - no slack notifications will be sent')
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '10', daysToKeepStr: '60'))
    }

    stages {

        stage('Notify started') {
            steps {
                notifySlack("started (${buildCause()})", 'lightskyblue', this)
                notifyEmail("started (${buildCause()})", this)
            }
        }

        stage('Build') {
            when {
                expression { return !params.RELEASE }
            }
            steps {
                failWhen condition: readMavenPom(file: 'pom.xml').with {
                    it.version?.trim()
                }, message: 'Root pom.xml file should contains version. Fix inconsistency in pom.xml.'

                buildWithMaven configId: 'maven-settings', goals: ['clean', 'install'], jvmParameters: [skipObfuscation: 'true']
            }
        }

        stage('deploy to CT') {
            when {
                expression { return params.DEPLOY_TO_CT }
            }
            steps {
                failWhen condition: readMavenPom(file: 'pom.xml').with {
                    it.version?.trim()
                }, message: 'Root pom.xml file should contains version. Fix inconsistency in pom.xml.'

                failWhen condition: mavenVersion().endsWith('-SNAPSHOT'), message: 'Cannot deploy snapshot artifact. Fix project version in pom.xml.'

                dir('testbundle-package') {
                    buildWithMaven configId: 'maven-settings', clean: false, goals: ['bundle:import']
                }
            }
        }

        stage('Release') {
            when {
                expression { return params.RELEASE }
            }
            stages {
                stage('Build and Deploy release') {
                    steps {
                        failWhen condition: readMavenPom(file: 'pom.xml').with {
                            it.version?.trim()
                        }, message: 'Root pom.xml file should contains version. Fix inconsistency in pom.xml.'

                        failWhen condition: mavenVersion().endsWith('-SNAPSHOT'), message: 'Cannot deploy snapshot artifact. Fix project version in pom.xml.'

                        // Place your bundle sub-module name here
                        dir('testbundle-package') {
                            buildWithMaven configId: 'maven-settings', goals: ['clean', 'deploy']
                        }
                    }
                }

                stage('Make a tag and push') {
                    steps {
                        shellScript "git tag ${mavenVersion()}"
                        shellScript 'git push --tags'
                    }
                }
            }
        }

    }

    post {
        success {
            notifySlack('is successful', 'forestgreen', this)
            notifyEmail('is successful', this)
        }
        unstable {
            notifySlack('is unstable', 'red', this)
            notifyEmail('is unstable', this)
        }
        failure {
            notifySlack('failed', 'red', this)
            notifyEmail('failed', this)
        }
        aborted {
            notifySlack('was aborted', 'red', this)
            notifyEmail('was aborted', this)
        }
    }
}

def notifySlack(String buildMessage, String color, def script) {
    final String slackChannel = script.params.SLACK_CHANNEL
    if (slackChannel.isEmpty()) {
        return
    }
    withCredentials([string(credentialsId: 'slack_auth', variable: 'SLACK_AUTH_PSW')]) {
        script.slackMessage(buildMessage: buildMessage, color: color, token: SLACK_AUTH_PSW, channel: slackChannel)
    }
}

def notifyEmail(String buildMessage, def script) {
    final def env = script.env
    if (emails.isEmpty()) {
        return
    }
    script.emailext(
            subject: "'${env.JOB_NAME} [${env.BUILD_NUMBER}]' ${buildMessage} ",
            to: params.EMAILS,
            body: """
'${env.JOB_NAME} [${env.BUILD_NUMBER}]' ${buildMessage} :
Check console output at "${env.JOB_NAME} [${env.BUILD_NUMBER}]"

"""
    )

}
