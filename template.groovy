def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def hcddSettings = new hcdd.Settings()
    hcddSettings.org = 'EG
    hcddSettings.team = 'TC'
    hcddSettings.product = 'Rev'
    hcddSettings.component = "${config.project}"

    def buildName = ""
    def newVersion = ""
    def branch = ""
    def isRelease = false
    def pomversion = ""
    def deployJars = true
    def deployRpms = true
    def settingsFile = "./sd_tools/config/maven/settings.xml"
    def activeProfiles = "-P build"
    String cmd = "/ctcmn/cmn-tools/maven/default/bin/mvn clean compile ${activeProfiles} -gs  ${settingsFile} -s ${settingsFile} -DskipTests -f  ${config.POM}  -Dhttps.protocols=TLSv1.2"

    String artifactVersion
    String artifactRelease

    def pipelineTimeout = 180
    if (config.timeout_minutes) {
        pipelineTimeout = config.timeout_minutes
    }

        com.ge.health.ct.devops.CTBuildManager buildMgr =
                       new com.ge.health.ct.devops.CTBuildManager(
                       this,
                       config.project,
                       config.POM,
                       './javabuild/mvn-conf/settings.xml',
                       env.BRANCH_NAME,
                       env.JOB_NAME)

        com.ge.health.ct.devops.CTSonar sonar = new com.ge.health.ct.devops.CTSonar(
                       this, config.POM, env.BRANCH_NAME, settingsFile)

    com.ge.health.ct.devops.CTUtilities utilities = new com.ge.health.ct.devops.CTUtilities()

    pipeline {
        agent { label 'ctwaus3b364' }
        options {
            timeout(time: pipelineTimeout, unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '3'))
            timestamps()
        }
        stages {
                       stage('initialize') {
                               steps {
                                      script {
                        echo "====================> Setup stage on ${env.hostname}"
                        updateGitlabCommitStatus(name:'initialize', state: 'running')
                        buildMgr.initialize()
                        buildMgr.addRepo('sd_tools', 'git@gitlab-gxp.cloud.health.ge.com:CTSD/sd_tools.git')
                        buildMgr.addRepo('util', 'git@gitlab-gxp.cloud.health.ge.com:CTSD/util.git')
                        updateGitlabCommitStatus(name:'initialize', state: 'success')
                                      }
                               }
                       }
            /**
             * build
             *  - build jars
             *  - build rpms
             */
            stage('build') {
                environment {
                    MAVEN_OPTS = "-Xms2048m -Xmx4096m -XX:MaxPermSize=3072m"
                    RELEASE_NUMBER = "${env.BUILD_NUMBER}"
                    JAVA_HOME = "/usr/java/jdk1.7.0_79/"
                    NDDSHOME = "/ctcmn/cmn-tools/rti-dds/rti_connext_dds-5.3.1/"
                    MATLAB_HOME = "/ctcmn/cmn-tools/matlab/R2016a"
                    PATH = "/ctcmn/cmn-tools/nodejs/default/bin:$PATH"
                }

                steps {
                    script {
                        echo "====================> Setup for ${config.project} on ${env.hostname} <===================="
                        updateGitlabCommitStatus(name: "setup", state: 'running')

                        branch = env.BRANCH_NAME
                        env.VIEW_ROOT = env.WORKSPACE
                        env.PROJECT_HOME = env.WORKSPACE
                        env.BUILD_REPOSITORY = env.PROJECT_HOME + '/build/repository/'
                        activeProfiles = activeProfiles + " " + (config.additionalMavenProfiles?.trim() ? config.additionalMavenProfiles : "")


                        deployRpms = config.PACKAGE_POM?.trim();
                        deployJars = config.POM?.trim();
                        def pomFile = deployJars ? config.POM : config.PACKAGE_POM
                        def pom = readMavenPom file: pomFile
                        //majorVersion = pomversion.split('-')[0]

                        // branch check - determines RELEASE or SNAPSHOT
                        //def masterBranch = 'master'
                        echo "====================> Building branch '${branch}' <===================="

                        //FIXME: Need to figure out a way to find the rpm version
                        artifactVersion = "2.0.1"
                        artifactRelease = "${env.BUILD_NUMBER}"
                        newVersion = "${artifactVersion}-${env.BUILD_NUMBER}"
                        buildName = "${pom.getArtifactId()}-${newVersion}"
                       isRelease = utilities.isReleaseBranch(env.BRANCH_NAME)
                        echo "=================>${isRelease}<===================="
                        if (isRelease){
                            pomversion = pom.getVersion()
                            artifactVersion = pomversion.split('-')[0]
                            newVersion = "${artifactVersion}-${env.BUILD_NUMBER}"
                            buildName = "${pom.getArtifactId()}-${newVersion}"
                        } else {
                            isRelease = false;
                            artifactVersion = "${branch}_${artifactVersion}"
                            buildName = "${pom.getArtifactId()}-${newVersion}"
                        }

                        sh 'printenv'
                        echo "[BUILD-REPO-CLEANUP] Deleting build repository - ${env.BUILD_REPOSITORY}"
                        dir("${env.BUILD_REPOSITORY}") {
                            deleteDir()
                        }
                        updateGitlabCommitStatus(name: 'setup', state: 'success')

                        echo "====================> Build for ${buildName} on ${env.hostname} <===================="
                        updateGitlabCommitStatus(name: 'build', state: 'running')

                        // *********** Build Jar Artifacts ***************
                        if (deployJars) {
                            echo "====================> Building JAR artifacts <===================="
                            sh "mvn clean deploy ${activeProfiles} -gs  ${settingsFile} -s ${settingsFile} -DskipTests -f ${config.POM}"
                        }

                        // *********** Build RPM Artifacts ***************
                        if (deployRpms) {
                            echo "====================> Building RPM artifacts <===================="
                            sh "mvn clean deploy ${activeProfiles} -gs ${settingsFile} -s ${settingsFile} -f ${config.PACKAGE_POM} -DRPMV=${artifactVersion} -DRPMR=${artifactRelease}"
                        }
                        updateGitlabCommitStatus(name: 'build', state: 'success')
                    }
                }
            }
            /**
            * test
            *
            *  - execute unit test cases
            */
            stage('unit test') {
                environment {
                DISPLAY=":99"
                } 
                when {
                    expression {
                        //return false;
                        return (config.allowUnitTestExecution == 'true');
                    }
                }
                options {
                    skipDefaultCheckout()
                }
                steps {
                    script {
                        try {
                            echo "====================> ${buildName} - executing junit tests <===================="
                            updateGitlabCommitStatus(name: 'unit test', state: 'running')
                            sh "Xvfb :99 & export DISPLAY=:99"
                            sh "mvn test -fae ${activeProfiles} -gs  ${settingsFile} -s ${settingsFile} -f ${config.POM}"
                            updateGitlabCommitStatus(name: 'unit test', state: 'success')
                        } catch (err) {
                            updateGitlabCommitStatus(name: 'unit test', state: 'failed')
                            echo "====================> Recording failed unittest case results"
                            junit '**/target/surefire-reports/*.xml'
                            throw err
                        }
                    }
                }
            }
            /**
                       * Sonar Analysis
                       * Scanning Source Code
                       */
                       stage('sonar analysis') {
                environment {
                    SONAR_USER_HOME = "${WORKSPACE}"
                    }
                               when {
                                      expression { 
                                          //return false; 
                                          return (config.runSonarAnalysis == 'true');
                                      }
                               }
                               options { skipDefaultCheckout() }
                               steps {
                                      script {
                                              try {
                                                      updateGitlabCommitStatus(name: 'sonar', state: 'running')
                                                      echo "====================>Sonar Analysis began on ${env.hostname} <===================="
                                                      sonar.run(buildMgr.isRelease, "target/sonar/report-task.txt")
                                                      echo "====================>Sonar gate check running on ${env.hostname} <===================="
                                                      sonar.gateCheck()
                                                      updateGitlabCommitStatus(name: 'sonar', state: 'success')
                                              } catch (err) {
                                                      updateGitlabCommitStatus(name: 'sonar', state: 'failed')
                                                      throw err
                                              }
                                      }
                               }
                       }

            /**
             * deploy
             * Publishing he artifacts (RPMs and JARs)
             *
             */
            stage('deploy') {
                options {
                    skipDefaultCheckout()
                }

                steps {
                    script {
                        def artServer = Artifactory.server 'hc-us-east-aws-artifactory'
                        artServer.setBypassProxy(true)

                        def buildInfo = Artifactory.newBuildInfo()
                        buildInfo.retention(maxBuilds: 10, maxDays: 14, deleteBuildArtifacts: true, async: true)
                        buildInfo.env.collect()
                        buildInfo.name = buildName
                        env.STAGING_REPOSITORY = env.WORKSPACE + '/staging'

                        // *********** Upload Artifacts to Artifactory ***************
                        echo "====================> Deploy for ${buildName} on ${env.hostname} <===================="
                        updateGitlabCommitStatus(name: 'deploy', state: 'running')

                        def specs = new java.util.ArrayList();
                        def releaseTarget = isRelease ? 'maven-release-ctprem/rpms/' : 'maven-snapshot-ctprem/rpms/'
                        if (deployRpms) {
                            specs.add([pattern:  './packages/rpmstaging/*.rpm' , target:  releaseTarget ,
                                       recursive :  'true' , flat:  'true' ])
                        }

                        if (isRelease && deployJars) {
                            specs.add([pattern:  './packages/jarstaging/(*)' , target: 'maven-release-ctprem/{1}' ,
                                       recursive : 'true' , flat: 'true'])
                        }

                        if(specs.size() > 0) {
                            sh('#!/bin/sh -e\n' + 'rm -rf `find ./packages/jarstaging -name "*.md5"`')
                            sh('#!/bin/sh -e\n' + 'rm -rf `find ./packages/jarstaging -name "*.sha1"`')

                            def uploadSpec = groovy.json.JsonOutput.toJson(specs)
                            uploadSpec = "{ \"files\" : " + uploadSpec + " } "
                            echo "Artifacts Upload Specification : ${uploadSpec}"

                            artServer.upload(uploadSpec, buildInfo)
                            echo "buildInfo: ${buildInfo}"
                            artServer.publishBuildInfo(buildInfo)

                            if(deployRpms) {
                                //NOTE: Do not upload RPMS to ct-build-results
                                //sh "./sd_tools/scripts/publish/add2repo.py --verbose -d ./packages/rpmstaging/ '*.rpm' revolution"
                                sh "./sd_tools/scripts/publish/ctwaurepo/add2repo.py --verbose -d ./packages/rpmstaging/ '*.rpm' revolution"
                                updateGitlabCommitStatus(name: 'deploy', state: 'success')
                            }
                        }
                    }
                }
            }

            /**
             * Build Sanity
             * We want to skip this step if config.sanity_host is null or empty 
             * If config.sanity_host has a value, we want to skip this step if we 
             *  are on a release branch. This is because our process requires the 
             *  sanity to be run by the snapshot build and the merge request will 
             *  not be accepted unless sanity passes for the snapshot.
             */
            stage('sanity') {
                options { skipDefaultCheckout() }
                when {
                    expression {
                        if (config.sanity_host) {
                            return !isRelease
                        }
                        return false
                    }
                }
                steps {
                    script {
                        echo "====================> Sanity for ${buildName} on ${env.hostname} <===================="
                       build_sanity {
                            sanity_host = config.sanity_host
                            build_version = artifactVersion
                            build_release = artifactRelease
                        }
                    }
                }
            }

            /**
             * releasenote
             * Publishing the releasenotes to Git
             *
             */            
            stage('releasenotes') {
                when {
                    expression {
                        return isRelease
                    }
                }
                steps {
                    script {
                        String commit = "${env.GIT_COMMIT}"
                        String last_successful_commit = env.GIT_PREVIOUS_SUCCESSFUL_COMMIT ? "${env.GIT_PREVIOUS_SUCCESSFUL_COMMIT}" : null
                        String tag = "${env.BRANCH_NAME}_${artifactVersion}_${env.BUILD_NUMBER}"

                        releaseNotes {
                            branch_name = branch
                            version = artifactVersion
                            release = artifactRelease
                            current_commit = commit
                            previous_successful_commit = last_successful_commit
                            component_name = config.component
                        }
                        com.ge.health.ct.devops.CTUtilities.pushTagtoGit(this, tag)
                    }
                }
            }

            /**
             * teardown
             * remove this job, cleanup the workspace
             * This should only happen if the previous steps passed
             * If there is a failure, we do not want to cleanup
             */
            stage('teardown') {
                options {
                    skipDefaultCheckout()
                }
                steps {
                    echo "====================> Teardown for ${buildName} on ${env.hostname} <===================="
                    updateGitlabCommitStatus(name:'teardown', state: 'running')
                    cleanWs()
                    updateGitlabCommitStatus(name:'teardown', state: 'success')
                }
            }
        }

        /**
         * Post actions
         */

               post {
                     always {
                echo "Cleaning workspace ${env.WORKSPACE} on ${env.hostname}"
                cleanWs()
            }
                       success {
                               script{
                                      echo '====================> POST success!'
                                       jaas_sensor_postjob{ settings = hcddSettings }

                                       buildMgr.postSuccess(config.email)
                                       updateGitlabCommitStatus(name:'build', state: 'success')
                               }
                       }

                       failure {
                               script {
                                      echo '====================> POST failure!'
                                       jaas_sensor_postjob{ settings = hcddSettings }

                                       buildMgr.postFailure(config.email)
                                       updateGitlabCommitStatus(name:'build', state: 'failed')
                               }
                       }
               } // post
    }
}

