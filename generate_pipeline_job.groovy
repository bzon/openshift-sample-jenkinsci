pipelineJob('PetClinic-App-Pipeline') {
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
def scmURL = 'git@gitlab:adopadmin/spring-petclinic.git' 

stage 'build: package & Junit'
node ('docker') {
  def mvnHome = tool name: 'ADOP Maven', type: 'hudson.tasks.Maven$MavenInstallation'
  gitlabCommitStatus("Maven Build") {
    checkout([$class: 'GitSCM', branches: [[name: 'origin/${gitlabSourceBranch}']], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'adop-jenkins-master', name: 'origin', refspec: '+refs/heads/*:refs/remotes/origin/* ', url: "${scmURL}" ]]])
    sh "${mvnHome}/bin/mvn package "
    junit '**/target/surefire-reports/TEST-*.xml'
  }
}

stage 'code quality: sonar'
node ('docker') {
  def mvnHome = tool name: 'ADOP Maven', type: 'hudson.tasks.Maven$MavenInstallation' 
  gitlabCommitStatus("Code Quality") {
      sh "${mvnHome}/bin/mvn sonar:sonar -Dsonar.host.url=http://sonar:9000/sonar -Dsonar.login=adopadmin -Dsonar.password=bryan123 -Dsonar.jdbc.url='jdbc:mysql://sonar-mysql:3306/sonar?useUnicode=true&characterEncoding=utf8&rewriteBatchedStatements=true' -Dsonar.jdbc.username=sonar -Dsonar.jdbc.password=sonar"
  }
}

stage 'deploy to openshift: dev environment'
node ('docker') {
  gitlabCommitStatus("Deploy to Dev") {
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'oc-login', passwordVariable: 'OC_PASSWORD', usernameVariable: 'OC_USER']]) {
      sh \'''#!/bin/bash -e
      APP_NAME=java-${gitlabSourceBranch}
      OC_PROJECT=dev-env
      oc login $OC_HOST -u $OC_USER -p $OC_PASSWORD --insecure-skip-tls-verify=true
      oc project ${OC_PROJECT}
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
      sleep 20
      \'''
    }
  }
}

stage 'regression test: cucumber & selenium'
node ('docker') {
  def mvnHome = tool name: 'ADOP Maven', type: 'hudson.tasks.Maven$MavenInstallation'
  gitlabCommitStatus("Regression Test") {
    env.PATH = "${mvnHome}/bin:${env.PATH}"
    checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'regression-test']], submoduleCfg: [], userRemoteConfigs: [[url: 'https://github.com/Accenture/adop-cartridge-java-regression-tests']]]
    sh \'''#!/bin/bash -e
    CONTAINER_NAME="owasp_zap-${gitlabSourceBranch}"
    OC_PROJECT=dev-env
    APP_URL="http://java-${gitlabSourceBranch}-${OC_PROJECT}.${OC_APPS_DOMAIN}/petclinic"
    
    echo "Starting OWASP ZAP Intercepting Proxy"
    cd regression-test/
    docker rm -f $CONTAINER_NAME | true
    docker run -it -d --net=$DOCKER_NETWORK_NAME -e affinity:container==jenkins-slave --name ${CONTAINER_NAME} -P nhantd/owasp_zap start zap-test
    echo "Sleeping for 30 seconds.. Waiting for OWASP Zap proxy to be up and running.."
    sleep 30
    echo "Starting Selenium test through maven.."
    mvn clean -B test -DPETCLINIC_URL=${APP_URL} -DZAP_IP=${CONTAINER_NAME} -DZAP_PORT=9090 -DZAP_ENABLED=true
    docker rm -f $CONTAINER_NAME
    \'''
    step([$class: 'CucumberReportPublisher', fileExcludePattern: '', fileIncludePattern: '', ignoreFailedTests: false, jenkinsBasePath: '', jsonReportDirectory: 'regression-test/target', parallelTesting: false, pendingFails: false, skippedFails: false, undefinedFails: false])
  }    
}

''')
            sandbox()
        }
    }
}
