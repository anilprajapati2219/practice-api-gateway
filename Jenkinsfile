pipeline {
    agent any
    options {
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '15', artifactDaysToKeepStr: '10', artifactNumToKeepStr: '10'))
    }

    environment {
        APP_NAME = 'practice-api-gateway'
        IMAGE_NAME = 'practice-dashboard-gateway'

        // Pulled from Jenkins Credentials (Secret text) — same convention
        // as integration-dashboard-service's Jenkinsfile. These get baked
        // into the deployed k8s Secret on every "Deploy to DEV" run (see
        // that stage below), NOT committed to any file in this repo.
        // AZURE_CLIENT_SECRET and JWT_SECRET must exist as Jenkins
        // credentials with exactly these IDs, or this build will fail at
        // the point it tries to resolve them.
        AZURE_CLIENT_ID     = credentials('AZURE_CLIENT_ID')
        AZURE_TENANT_ID     = credentials('AZURE_TENANT_ID')
        AZURE_CLIENT_SECRET = credentials('AZURE_CLIENT_SECRET')
        AZURE_REDIRECT_URI  = credentials('AZURE_REDIRECT_URI')
        JWT_SECRET          = credentials('JWT_SECRET')
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

                      # Secret values come from Jenkins Credentials (see the
                      # environment block above), never from a committed
                      # file. --from-literal passes each value as its own
                      # argument, so odd characters in the actual secret
                      # (quotes, $, backslashes, ...) can't break the
                      # generated YAML the way hand-built JSON/string
                      # interpolation could.
                      kubectl create secret generic practice-dashboard-gateway-secret \
                        --namespace practice-dashboard-gateway \
                        --from-literal=AZURE_TENANT_ID="${AZURE_TENANT_ID}" \
                        --from-literal=AZURE_CLIENT_ID="${AZURE_CLIENT_ID}" \
                        --from-literal=AZURE_CLIENT_SECRET="${AZURE_CLIENT_SECRET}" \
                        --from-literal=AZURE_REDIRECT_URI="${AZURE_REDIRECT_URI}" \
                        --from-literal=JWT_SECRET="${JWT_SECRET}" \
                        --dry-run=client -o yaml | kubectl apply -f -

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