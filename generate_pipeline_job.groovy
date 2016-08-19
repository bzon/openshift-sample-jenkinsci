pipelineJob('openshift-ci-pipeline') {
    triggers {
        gitlabPush {
            buildOnMergeRequestEvents(false)
            buildOnPushEvents(true)
            enableCiSkip(true)
            setBuildDescription(true)
            addNoteOnMergeRequest(false)
            rebuildOpenMergeRequest('never')
            addVoteOnMergeRequest(false)
            acceptMergeRequestOnSuccess(false)
            targetBranchRegex('.*feature-*.+')
        }
    }
    definition {
        cps {
          script('''
properties properties: [[$class: 'GitLabConnectionProperty', gitLabConnection: 'ADOP Gitlab']]

node ('docker') {
  def mvnHome = tool name: 'ADOP Maven', type: 'hudson.tasks.Maven$MavenInstallation'
  def scmURL = 'git@gitlab:adopadmin/os-sample-java-web.git' 

   stage 'Checkout'
   checkout([$class: 'GitSCM', branches: [[name: 'origin/${gitlabSourceBranch}']], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'adop-jenkins-master', name: 'origin', refspec: '+refs/heads/*:refs/remotes/origin/* ', url: "${scmURL}" ]]])

   stage 'Build'
   gitlabCommitStatus("Maven Build") {
     sh "${mvnHome}/bin/mvn clean install"
   }

   stage 'Unit Test'
   gitlabCommitStatus("Unit Testing") {
     echo "Unit testing.."
   }
   
   stage 'SonarQube Code Quality'
   gitlabCommitStatus("Code Quality") {
     sh "${mvnHome}/bin/mvn sonar:sonar -Dsonar.host.url=http://sonar:9000/sonar -Dsonar.login=adopadmin -Dsonar.password=bryan123 -Dsonar.jdbc.url='jdbc:mysql://sonar-mysql:3306/sonar?useUnicode=true&characterEncoding=utf8&rewriteBatchedStatements=true' -Dsonar.jdbc.username=sonar -Dsonar.jdbc.password=sonar"
   }

   stage 'Deploy'
   gitlabCommitStatus("Deploy") {
     sh \'''#!/bin/bash -e

     APP_NAME=${gitlabSourceRepoName}
     PROJECT=develop-feature

     oc project ${PROJECT}

     if [[ $(oc get deploymentconfigs | grep ${APP_NAME} | wc -l) -eq 0 ]]; 
     then
       oc new-build -i wildfly:10.0 --binary=true --context-dir=/ --name=${APP_NAME}
       oc start-build ${APP_NAME} --from-dir=target/ --follow
       oc logs -f bc/${APP_NAME}
       oc new-app -i ${APP_NAME}
       oc expose svc/${APP_NAME}
     else
       oc start-build ${APP_NAME} --from-dir=target/ --follow
     fi
    \'''
    echo "Workaround for fixing the issue where gitlab webhook config does not persist. See issue https://github.com/jenkinsci/gitlab-plugin/issues/395"
    build 'generate-job'
   }
}

''')
            sandbox()
        }
    }
}
