#!groovy
def repourl(repo){
  "http://github.com/${repo}"
}

def repodir(repo){
  repo.tokenize('/').last()  
}

def checkout_step(repo){
  echo 'inside checkout'
  echo repourl(repo)
  echo repodir(repo)
  checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: repodir(repo)]], submoduleCfg: [], userRemoteConfigs: [[url: repourl(repo)]]])
}

node{

  r1='ekorekin/repo1'
  r2='ekorekin/repo2'
  stage 'Check for changed repos'
  repos=[r1,r2]
  changed_repos=[]


  dir('prevBuild'){
    step ([$class: 'CopyArtifact', projectName: env.JOB_NAME, filter: '*-GIT_COMMIT']);
  }

  for(int i = 0; i < repos.size(); i++){
    repo=repos[i]

    sh "git ls-remote ${repourl(repo)} HEAD | cut -f1 > ${repodir(repo)}-GIT_COMMIT"
    git_commit=readFile("${repodir(repo)}-GIT_COMMIT")
    git_commit_prev=readFile("prevBuild/${repodir(repo)}-GIT_COMMIT")
    if(git_commit !=git_commit_prev){
        changed_repos.add(repo)
    }
  }

  echo "Changed repos: ${changed_repos.join(',')}"

  archive('*-GIT_COMMIT')

  if(r1 in changed_repos){
    stage 'r1 changed'
    checkout_step(r1)
  }

  if(r2 in changed_repos){
    stage 'r2 changed'
    checkout_step(r2)
  }

}
