pipelineJob('PetClinic-App-Pipeline') {
    configure { project ->
        project / 'triggers' <<  'com.dabsquared.gitlabjenkins.GitLabPushTrigger'(plugin: "gitlab-plugin@1.4.0")  {
        spec()
        triggerOnPush(false)
        triggerOnMergeRequest(true)
        triggerOpenMergeRequestOnPush('never')
        triggerOnNoteRequest(true)
        noteRegex('jenkins rebuild')
        ciSkip('true')
        skipWorkInProgressMergeRequest(false)
        setBuildDescription(true)
        branchFilterType('RegexBasedFilter')
        includeBranchesSpec()
        excludeBranchesSpec()
  		targetBranchRegex('master')                 
        }
    }
    definition {
        cps {
          script('''
//properties properties: [[$class: 'GitLabConnectionProperty', gitLabConnection: 'ADOP Gitlab']]

def scmURL = 'git@gitlab:adopadmin/spring-petclinic.git' 

addGitLabMRComment '[Jenkins]: A Pipeline has started.'

gitlabBuilds(builds: ["junit test & compile", "sonar code quality", "deploy to dev", "regression test", "performance test"]) {
  stage 'junit test & compile'
  node ('docker') {
    def mvnHome = tool name: 'ADOP Maven', type: 'hudson.tasks.Maven$MavenInstallation'
    gitlabCommitStatus('junit test & compile') {
      checkout changelog: true, poll: true, scm: [$class: 'GitSCM', branches: [[name: "origin/${env.gitlabSourceBranch}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PreBuildMerge', options: [fastForwardMode: 'FF', mergeRemote: 'origin', mergeStrategy: 'default', mergeTarget: "master"]]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'adop-jenkins-master', name: 'origin', url: "${scmURL}"]]]
      sh "${mvnHome}/bin/mvn package "
      junit '**/target/surefire-reports/TEST-*.xml'
    }
  }
  
  stage 'sonar code quality'
  node ('docker') {
    def mvnHome = tool name: 'ADOP Maven', type: 'hudson.tasks.Maven$MavenInstallation' 
    gitlabCommitStatus('sonar code quality') {
        sh "${mvnHome}/bin/mvn sonar:sonar -Dsonar.host.url=http://sonar:9000/sonar -Dsonar.login=adopadmin -Dsonar.password=bryan123 -Dsonar.jdbc.url='jdbc:mysql://sonar-mysql:3306/sonar?useUnicode=true&characterEncoding=utf8&rewriteBatchedStatements=true' -Dsonar.jdbc.username=sonar -Dsonar.jdbc.password=sonar"
    }
  }
  
  stage 'deploy to dev'
  node ('docker') {
    gitlabCommitStatus('deploy to dev') {
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
  stage 'regression test'
  node ('docker') {
    def mvnHome = tool name: 'ADOP Maven', type: 'hudson.tasks.Maven$MavenInstallation'
    gitlabCommitStatus('regression test') {
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
  
  stage 'openshift: scale up environment'
  node ('docker') {
      sh \'''#!/bin/bash -e
      APP_NAME=java-${gitlabSourceBranch}
      SCALE_COUNT=5
      REPLICATE_CONTROLLER_NAME=$(oc get rc -l app=${APP_NAME} | tail -1 | awk '{print $1}')
      oc scale --replicas=${SCALE_COUNT} rc ${REPLICATE_CONTROLLER_NAME}
      until [[ $( oc get pods | grep ${APP_NAME} | grep Running  | wc -l) -eq ${SCALE_COUNT} ]]; 
      do
         echo "Waiting for the service ${APP_NAME} to be scaled up to 5.."
         sleep 3
      done
      sleep 5
      \'''
  }
  
  stage 'performance test'
  node ('docker') {
    gitlabCommitStatus('performance test') {
      def antHome = tool name: 'ADOP Ant', type: 'hudson.tasks.Ant$AntInstallation'
      def mvnHome = tool name: 'ADOP Maven', type: 'hudson.tasks.Maven$MavenInstallation'
      env.PATH = "${antHome}/bin:${mvnHome}/bin:${env.PATH}"
      sh \'''#!/bin/bash -e
      JMETER_TESTDIR=jmeter-test
      rm -fr $JMETER_TESTDIR
      mkdir -p $JMETER_TESTDIR
      cp -rp $(ls | grep -v $JMETER_TESTDIR) $JMETER_TESTDIR/
      
      if [ -e ../apache-jmeter-2.13.tgz ]; then
  	  cp ../apache-jmeter-2.13.tgz $JMETER_TESTDIR
      else
  	  wget https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-2.13.tgz
        cp apache-jmeter-2.13.tgz ../
        mv apache-jmeter-2.13.tgz $JMETER_TESTDIR
     fi
     
     cd $JMETER_TESTDIR
     OC_PROJECT=dev-env
     PETCLINIC_HOST=java-${gitlabSourceBranch}-${OC_PROJECT}.${OC_APPS_DOMAIN}
     tar -xf apache-jmeter-2.13.tgz
     echo 'Changing user defined parameters for jmx file'
     sed -i 's/PETCLINIC_HOST_VALUE/'"${PETCLINIC_HOST}"'/g' src/test/jmeter/petclinic_test_plan.jmx
     sed -i 's/PETCLINIC_PORT_VALUE/80/g' src/test/jmeter/petclinic_test_plan.jmx
     sed -i 's/CONTEXT_WEB_VALUE/petclinic/g' src/test/jmeter/petclinic_test_plan.jmx
     sed -i 's/HTTPSampler.path"></HTTPSampler.path">petclinic</g' src/test/jmeter/petclinic_test_plan.jmx
     cd ../
     ant -f $JMETER_TESTDIR/apache-jmeter-2.13/extras/build.xml -Dtestpath=/workspace/PetClinic-App-Pipeline/${JMETER_TESTDIR}/src/test/jmeter -Dtest=petclinic_test_plan
     
     cd /workspace/PetClinic-App-Pipeline/src/test/gatling
     sed -i "s+###TOKEN_VALID_URL###+http://${PETCLINIC_HOST}+g" src/test/scala/default/RecordedSimulation.scala
     sed -i "s/###TOKEN_RESPONSE_TIME###/10000/g" src/test/scala/default/RecordedSimulation.scala
     mvn gatling:execute
     \'''
     
     step([$class: 'GatlingPublisher', enabled: true])
     publishHTML(target: [allowMissing: false, alwaysLinkToLastBuild: false, keepAll: false, reportDir: 'jmeter-test/src/test/jmeter', reportFiles: 'petclinic_test_plan.html', reportName: 'Jmeter Report'])
     
     // Scale Down the service
     sh \'''#!/bin/bash -e
     APP_NAME=java-${gitlabSourceBranch}
     REPLICATE_CONTROLLER_NAME=$(oc get rc -l app=${APP_NAME} | tail -1 | awk '{print $1}')
     oc scale --replicas=1 rc ${REPLICATE_CONTROLLER_NAME}
     \'''
    }
  }
  
  stage 'merge request approval'
  timeout(time:1, unit:'DAYS') {
    input message:'All testing has passed. Approve merge request?', submitter: 'administrators'
  }
  node ('docker') {
      acceptGitLabMR '[Jenkins]: All test has completed. Merge request accepted.'
  }
  
  addGitLabMRComment '[Jenkins]: Merged by Jenkins.'
  
}
''')
            sandbox()
        }
    }
}
