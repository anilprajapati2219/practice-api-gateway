pipeline {
    agent any
    options {
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '15', artifactDaysToKeepStr: '10', artifactNumToKeepStr: '10'))
    }

    environment {
        APP_NAME = 'practice-api-gateway'
        IMAGE_NAME = 'practice-dashboard-gateway'
    }

    stages {

        stage('Debug') {
            steps {
                echo "BRANCH_NAME = ${env.BRANCH_NAME}"
            }
        }

        stage('Build') {
            when {
                anyOf {
                    // changeRequest()
                    expression { env.BRANCH_NAME == 'feature' }
                    expression { env.BRANCH_NAME == 'dev' }
                }
            }
            steps {
                sh '''
                  export MAVEN_HOME=/opt/maven
                  export PATH=$PATH:$MAVEN_HOME/bin
                  mvn --version
                  mvn clean install -DskipTests
                '''
                stash name: 'jar', includes: 'target/*.jar'
            }
        }

        stage('Docker Build & Push') {
            when {
                allOf {
                    branch 'dev'
                    not { changeRequest() }
                }
            }
            environment {
                IMAGE_TAG = "${env.BUILD_NUMBER}"
            }
            steps {
                unstash 'jar'
                sh '''
                    docker build --no-cache --pull -t ${IMAGE_NAME}:${IMAGE_TAG} .
                    docker tag ${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_NAME}:latest
                    docker save ${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_NAME}:latest | sudo ctr -n k8s.io images import -
                '''

                writeFile file: 'image-tag.txt', text: IMAGE_TAG
                archiveArtifacts artifacts: 'image-tag.txt', fingerprint: true
            }
        }

        stage('Deploy to DEV') {
            when {
                branch 'dev'
                not { changeRequest() }
            }
            environment {
                IMAGE_TAG = "${env.BUILD_NUMBER}"
            }
            steps {
                withCredentials([file(credentialsId: 'practice-dashboard-gateway-kubeconfig', variable: 'KUBECONFIG')]) {

                    sh '''
                      set -x  
                      kubectl apply -f k8s/dev/gateway-deployment.yaml -n practice-dashboard-gateway
                      kubectl apply -f k8s/dev/gateway-ingress.yaml -n practice-dashboard-gateway
                      kubectl apply -f k8s/dev/gateway-service.yaml -n practice-dashboard-gateway

                      kubectl set image deployment/practice-dashboard-gateway \
                        practice-dashboard-gateway=${IMAGE_NAME}:${IMAGE_TAG} \
                        -n practice-dashboard-gateway

                      kubectl rollout status deployment/practice-dashboard-gateway \
                        -n practice-dashboard-gateway --timeout=120s
                    '''
                }
            }
        }

    }
}