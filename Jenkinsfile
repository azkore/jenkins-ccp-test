#!groovy

def repodir(repo){
  repo.tokenize('/').last()
}

def checkout_to(repo, dir){
  //echo repourl(repo)
  checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: dir]], submoduleCfg: [], userRemoteConfigs: [[url: repo]]])
}

def deploy(){
    //stub
}

def build(){
  sh 'virtualenv keystoneenv'
  sh '. keystoneenv/bin/activate && pip install fuel-ccp/ && \
      ccp --images-maintainer mirantis@mirantis.com \
        --images-namespace keystonebuild --images-tag latest \
        --repositories-names fuel-ccp-debian-base,fuel-ccp-openstack-base,fuel-ccp-keystone --builder-no-cache \
        --repositories-path containers/openstack \
        build -c keystone && deactivate'
}

node{
    
  buildme=0
  deployme=0

  ccp_keystone='https://github.com/ekorekin/fuel-ccp-keystone'
  os_keystone='https://github.com/ekorekin/keystone'
  fuel_ccp='https://git.openstack.org/openstack/fuel-ccp'
  stage 'Check for changed repos'
  repos=[fuel_ccp, ccp_keystone, os_keystone]
  changed_repos=[]


  dir('prevBuild'){
    try{
      step ([$class: 'CopyArtifact', projectName: env.JOB_NAME, filter: '*-GIT_COMMIT'])
    } catch (all) {
        
    }
  }

  for(int i = 0; i < repos.size(); i++){
    repo=repos[i]

    sh "git ls-remote ${repo} HEAD | cut -f1 > ${repodir(repo)}-GIT_COMMIT"
    git_commit=readFile("${repodir(repo)}-GIT_COMMIT")
    try{
      git_commit_prev=readFile("prevBuild/${repodir(repo)}-GIT_COMMIT")
    } catch(all) {
      git_commit_prev=0
    }
    if(git_commit !=git_commit_prev){
        changed_repos.add(repo)
    }
  }
  
  echo "Changed repos: ${changed_repos.join(' ')}"
      
  archive('*-GIT_COMMIT')

  if(fuel_ccp in changed_repos){
    echo 'fuel_ccp changed'
    checkout_to(fuel_ccp, 'fuel-ccp')
    buildme=1
  }
  
  if(ccp_keystone in changed_repos){
    echo 'ccp_keystone changed'
    ccp_keystone_dir='containers/openstack/fuel-ccp-keystone'
    git_commit_prev=readFile("prevBuild/${repodir(ccp_keystone)}-GIT_COMMIT").minus('\n')
    checkout_to(ccp_keystone, ccp_keystone_dir)
    dir(ccp_keystone_dir){
      sh "git diff --name-only ${git_commit_prev} HEAD > changedfiles"
      if(readFile('changedfiles').find('Dockerfile')){
        buildme=1
      }
      sh 'rm changedfiles'
    }
  }

  if(os_keystone in changed_repos){
    echo('os_keystone changed')
    checkout_to(os_keystone, 'containers/openstack/fuel-ccp-keystone/docker/keystone/keystone')
    buildme=1
  }

  if(buildme){
    stage 'build'
    build()
  }

  if(deployme){
    stage 'deploy'
    deploy()
  }
 
}
