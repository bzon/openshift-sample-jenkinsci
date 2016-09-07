# openshift-sample-jenkinsci

# Pre-requisite:

- Ensure that you have properly configured the Gitlab connnection in "Managed Jenkins" named the Gitlab Connection as "ADOP Gitlab".  
- Ensure that the spring-petclinic project is pushed in Gitlab.  

# SCM Checkout Style for different scenarios

- Checkout from Merge Request and merge with Master
```bash
checkout changelog: true, poll: true, scm: [$class: 'GitSCM', branches: [[name: "origin/${env.gitlabSourceBranch}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PreBuildMerge', options: [fastForwardMode: 'FF', mergeRemote: 'origin', mergeStrategy: 'default', mergeTarget: "${env.gitlabTargetBranch}"]]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'adop-jenkins-master', name: 'origin', url: "${scmURL}"]]]
```

- Checkout from Push and merge with Master
```bash
checkout changelog: true, poll: true, scm: [$class: 'GitSCM', branches: [[name: "origin/${env.gitlabSourceBranch}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PreBuildMerge', options: [fastForwardMode: 'FF', mergeRemote: 'origin', mergeStrategy: 'default', mergeTarget: "master"]]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'adop-jenkins-master', name: 'origin', url: "${scmURL}"]]]
```

- Checkout from Push
```bash
checkout([$class: 'GitSCM', branches: [[name: 'origin/${gitlabSourceBranch}']], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'adop-jenkins-master', name: 'origin', refspec: '+refs/heads/*:refs/remotes/origin/* ', url: "${scmURL}" ]]])
```
