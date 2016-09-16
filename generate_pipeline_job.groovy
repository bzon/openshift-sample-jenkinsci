pipelineJob('PetClinic-App-Pipeline') {
  configure { project ->
    project / 'triggers' / 'com.dabsquared.gitlabjenkins.GitLabPushTrigger'  {
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
    cpsScm {
      scm {
        git {
           remote {
            url('https://github.com/bzon/openshift-sample-jenkinsci.git')
          }
          	branch("*/master")
      	}
      }
      scriptPath('Jenkinsfile')
    }
  }
  
}
