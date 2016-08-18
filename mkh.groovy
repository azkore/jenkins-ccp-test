components=['keystone', 'horizon']
clonepath='microservices-repos'
art_key='AKCp2V5pDkduZrPJeExEG7jCkgLKKSZZhYxzz9BRK8VkuTzUJDHpq4rv6VGPsvd4hQg3DFCyP'
art_url='http://cz8164/artifactory/'
registry='http://docker-dev-local2.art.local'
registry_repo='docker-dev-local2'

def checkout_to(repo, dest_dir){
    checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: dest_dir]], submoduleCfg: [], userRemoteConfigs: [[url: repo]]])
    dir(dest_dir) {
        sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
    }
}

def checkout_ccp_repo(repo){
    full_repo_name="https://github.com/openstack/fuel-ccp-${repo}"
    checkout_dir="${clonepath}/fuel-ccp-${repo}"
    checkout_to(full_repo_name, checkout_dir)
}

def build_component(component){
    echo "Building ${component}"
}

def buildsteps=[:]
for (int i=0; i<components.size(); i++) {
    def c=components[i]
    buildsteps[c] = { 
        node { build_component(c) }
    }
}

def art_set_prop(repo, path, prop, value) {
    json="{\"multiValue\":false,\"property\":{\"name\":\"${prop}\"},\"selectedValues\":[\"${value}\"]}"
    sh "curl -H \"Content-Type: application/json\" -H \"X-JFrog-Art-Api:${art_key}\" -X POST \"${art_url}/ui/artifactproperties?path=${path}&repoKey=${repo}&recursive=true\" -d '${json}'"
}

def art_find(path, prop_list) {
    props="--props "
    def i=0
    for ( e in prop_list ) {
        if(i>0){ props="${props}\\;" }
        props="${props}${e.key}=${e.value}"
        i++
    }

    res=sh(returnStdout: true, script: "JFROG_CLI_LOG_LEVEL=ERROR jfrog rt s --url='http://localhost/artifactory' --apikey='AKCp2V5pDkduZrPJeExEG7jCkgLKKSZZhYxzz9BRK8VkuTzUJDHpq4rv6VGPsvd4hQg3DFCyP' ${props} ${path}").trim()
    
    if(res.contains("path")){
        1
    }
    else {
        0
    }
}

stage "Build base-tools"
node {
    ccpCommit=checkout_ccp_repo('debian-base')
    if(art_find(registry_repo, [ 'ccp-commit' : ccpCommit ])){
        echo "Image for the commit ${ccpCommit} already exists in Artifactory, skipping build"
        return
    }
    sh "ccp --repositories-noclone --repositories-path=./${clonepath} build -c base-tools"
    def image=docker.image('ccp/base-tools')
    docker.withRegistry(registry, 'registry-auth') {
        image.push()
    }
    art_set_prop(registry_repo, 'ccp/base-tools', 'ccp-commit', ccpCommit)
}


stage "Build components"
parallel buildsteps
