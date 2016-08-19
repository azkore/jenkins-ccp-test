stage "Prepare environment"
clonepath='microservices-repos'
art_key='AKCp2V5pDkduZrPJeExEG7jCkgLKKSZZhYxzz9BRK8VkuTzUJDHpq4rv6VGPsvd4hQg3DFCyP'
art_url='http://cz8164/artifactory/'
registry='http://docker-dev-local2.art.local'
registry_repo='docker-dev-local2'
art = Artifactory.server('art')

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

def test_component(component){
    echo "Testing ${component}"
    sh "sleep ${rand(5)}"
}

//@NonCPS //need to check if we really need this here, see https://support.cloudbees.com/hc/en-us/articles/204972960-The-pipeline-even-if-successful-ends-with-java-io-NotSerializableException
def art_set_prop(repo, path, properties) {
    def cmds=[]
    for(e in properties) {
        json="{\"multiValue\":false,\"property\":{\"name\":\"${e.key}\"},\"selectedValues\":[\"${e.value}\"]}"
        cmd="curl -H \"Content-Type: application/json\" -H \"X-JFrog-Art-Api:${art_key}\" -X POST \"${art_url}/ui/artifactproperties?path=${path}&repoKey=${repo}&recursive=true\" -d '${json}'"
        cmds.add(cmd)
        //echo cmd
        //sh cmd
        //e=null //need this to remove non-serializable var, see https://github.com/jenkinsci/pipeline-plugin/blob/master/TUTORIAL.md
        //FIXME1: not clear why but shell steps run only once
        //sh "curl -H \"Content-Type: application/json\" -H \"X-JFrog-Art-Api:${art_key}\" -X POST \"${art_url}/ui/artifactproperties?path=${path}&repoKey=${repo}&recursive=true\" -d '${json}'"
    }
    //this is a workaround, see FIXME1
    for (int i=0; i<cmds.size(); i++) {
        echo(cmds[i])
        sh cmds[i]
    }
}

def art_find(path, properties) {
    props="--props "
    def i=0
    for(e in properties) {
        if(i>0){ props="${props}\\;" }
        props="${props}${e.key}=${e.value}"
        i++
    }

    res=sh(returnStdout: true, script: "JFROG_CLI_LOG_LEVEL=ERROR jfrog rt s --url='http://localhost/artifactory' --apikey='AKCp2V5pDkduZrPJeExEG7jCkgLKKSZZhYxzz9BRK8VkuTzUJDHpq4rv6VGPsvd4hQg3DFCyP' ${props} ${path}").trim()
    res.contains("path")
}

def build_image(component, commits){
    echo "Looking for commits ${commits}..."
    if(art_find(registry_repo, commits)){
        echo "Image for the commits ${commits} already exists in Artifactory, skipping build"
        return
    }
    sh "ccp --repositories-noclone --repositories-path=./${clonepath} build -c base-tools"
    
    if(component=='debian-base'){
        image_name="ccp/base-tools"
    }
    else {
        image_name="ccp/${component}"
    }
    push_image(image_name, commits)
}

def push_image(name, commits){
    def image=docker.image(name)
    docker.withRegistry(registry, 'registry-auth') {
        image.push()
    }
    art_set_prop(registry_repo, name, commits)
}

def rand(n){
    Math.abs(new Random().nextInt() % n) + 1
}

def art_upload_file(repo, file){
        def uploadSpec = """{
        "files": [{
            "pattern": "${file}",
            "target": "${repo}/${file}"
        }]
        }"""
    
        art.upload(uploadSpec)
}

def art_download_file(repo, file){
        def downloadSpec = """{
        "files": [{
            "pattern": "${repo}/${file}",
            "target": "${file}"
        }]
        }"""
    
        art.download(downloadSpec)
}

stage "Building images"
node {
    
    debian_base_commits=['debian-base_commit': checkout_ccp_repo('debian-base')]
    build_image('debian-base', debian_base_commits)

    parallel (
        "openstack-base": {
        openstack_base_commits=debian_base_commits + ['openstack-base_commit': checkout_ccp_repo('openstack-base')]
        build_image('openstack-base', openstack_base_commits)
        
        parallel(
            "keystone": { 
                keystone_commits=openstack_base_commits + ['keystone_commit': checkout_ccp_repo('keystone')]
                build_image('keystone', keystone_commits)
                
            },
            "horizon": { 
                horizon_commits=openstack_base_commits + ['horizon_commit': checkout_ccp_repo('horizon')]
                build_image('horizon', horizon_commits)
            }
        )
        },

        "etcd": {
        etcd_commits=debian_base_commits + ['etcd_commit': checkout_ccp_repo('etcd')]
        build_image('etcd', etcd_commits)
        },
        
        "mariadb": {
        mariadb_commits=debian_base_commits + ['mariadb_commit': checkout_ccp_repo('mariadb')]
        build_image('mariadb', mariadb_commits)
        }
    )

}

def teststeps=[:]
for (int i=1; i<4; i++) {
    teststeps["Test ${i}"] = { 
        node { test_component("${i}") }
    }
}
teststeps[4]={
    node { 
        unittest4_props=mariadb_commits+['unittest4': 'green']
        if(art_find('test-repo', unittest4_props)){
            echo "unittest4 for ${mariadb_commits} is already green, skipping"
        }
        else {
            
            echo "Unittest 4"
            sh "sleep 15"
            
            sh "echo example > unittest4"
            art_upload_file("test-repo", "unittest4")
            art_set_prop("test-repo", "unittest4", unittest4_props)
        }
    }
}

stage "Unit tests"
parallel teststeps

stage "Deploy to staging"
node {
    echo "deploy to staging"
    art_download_file("ccp-conf","ccp-staging.conf")
    art_download_file("ccp-conf","ccp-globals.yaml")
    checkout_ccp_repo('entrypoint')
    sh 'ccp  --repositories-path=./microservices-repos --config-file=ccp-staging.conf deploy '
}

stage "Integration test"
node {
    //int_test_props=mariadb_commits.plus(keystone_commits.plus(horizon_commits.plus['integration_test': 'green']))
    int_test_props=mariadb_commits+keystone_commits+horizon_commits+['integration_test': 'green']
    
    if(art_find('test-repo', int_test_props)){
        echo "Integration test for ${int_test_props} is already green, skipping"
    }
    else {
        echo "Integration test"
        sh "sleep 30"
        
        sh "echo example > int_test"
        art_upload_file("test-repo", "int_test")
        art_set_prop("test-repo", "int_test", int_test_props)
    }
}

stage "Deploy to production" 
node {
    //input "Deploy to production?"
    echo "deploy to production"
    checkout_ccp_repo('entrypoint')
    art_download_file("ccp-conf","ccp-prod.conf")
    art_download_file("ccp-conf","ccp-globals.yaml")
    sh 'ccp  --repositories-path=./microservices-repos --config-file=ccp-prod.conf deploy '
}
