// CI for bank-open-api and the provider app beside it (provider-app/): compile, run every test, publish reports,
// build the images. With the k3d cluster (DEPLOY_TO_K3D=true, see docs/cluster.md) it deploys the API and, signed in
// as the test provider, takes consents and payments through it via Traefik; then it deploys the provider app and
// goes through it as its customer would.
pipeline {
    agent any

    options {
        timeout(time: 40, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
        disableConcurrentBuilds()
    }

    environment {
        // The k3d registry, as the host's Docker sees it; the cluster pulls the same names from it.
        REGISTRY = 'localhost:5050'
    }

    stages {
        stage('Build and test') {
            steps {
                // The executable bit can get lost on the way into git; the wrapper is a plain sh script.
                sh 'chmod +x mvnw && ./mvnw -B -ntp verify'
            }
            post {
                always {
                    junit testResults: 'target/surefire-reports/*.xml, target/failsafe-reports/*.xml',
                          allowEmptyResults: true
                    archiveArtifacts artifacts: 'target/site/jacoco/**', allowEmptyArchive: true
                }
            }
        }

        stage('Provider app: build and test') {
            steps {
                // A project of its own, built with the same wrapper.
                sh './mvnw -B -ntp -f provider-app/pom.xml verify'
            }
            post {
                always {
                    junit testResults: 'provider-app/target/surefire-reports/*.xml', allowEmptyResults: true
                }
            }
        }

        stage('Docker images') {
            steps {
                script {
                    // The commit names the images, so the cluster always shows which code runs.
                    env.IMAGE_TAG = sh(script: 'git rev-parse --short=12 HEAD', returnStdout: true).trim()
                }
                sh 'docker build -t bank-open-api:${IMAGE_TAG} -t bank-open-api:latest .'
                sh 'docker build -t provider-app:${IMAGE_TAG} -t provider-app:latest provider-app'
            }
        }

        stage('Deploy to k3d') {
            when {
                environment name: 'DEPLOY_TO_K3D', value: 'true'
            }
            steps {
                sh 'helm lint charts/bank-open-api'
                sh 'docker tag bank-open-api:${IMAGE_TAG} ${REGISTRY}/bank-open-api:${IMAGE_TAG}'
                sh 'docker push ${REGISTRY}/bank-open-api:${IMAGE_TAG}'
                // Waits until the new pod is ready; a release that never gets there is rolled back.
                // --set-string, because --set would turn a commit id made only of digits into a number.
                sh '''
                    helm upgrade --install bank-open-api charts/bank-open-api --namespace bank \
                        --set image.repository=${REGISTRY}/bank-open-api --set-string image.tag=${IMAGE_TAG} \
                        --rollback-on-failure --timeout 5m
                '''
                // Helm is done once the new pod is ready, but the old one answers on during its preStop pause,
                // until Traefik leaves it out: the smoke test would meet both versions. Wait until only pods of
                // this image are left.
                sh 'bash scripts/wait-for-version.sh bank bank-open-api ${IMAGE_TAG}'
            }
            post {
                failure {
                    sh 'kubectl --namespace bank get pods || true'
                    sh 'kubectl --namespace bank get events --sort-by=.lastTimestamp | tail -n 20 || true'
                }
            }
        }

        stage('Smoke test') {
            when {
                environment name: 'DEPLOY_TO_K3D', value: 'true'
            }
            steps {
                // The way a provider comes in: k3d's load balancer, Traefik's mutual TLS on the API's host, the
                // route, the rate limit. Jenkins is on k3d's Docker network, where the load balancer listens on
                // port 443; the test keeps the bank's and the API's host names and connects there. The test
                // provider's key and certificate, the bank's test CA and the test customer's password come from
                // the cluster's Secrets into a folder only this build sees.
                sh '''
                    export PKI_DIR="$(mktemp -d)"
                    trap 'rm -rf "$PKI_DIR"' EXIT
                    bash scripts/smoke-test-files.sh "$PKI_DIR"
                    bash scripts/smoke-test.sh https://k3d-bank-serverlb
                '''
            }
        }

        stage('Deploy the provider app') {
            when {
                environment name: 'DEPLOY_TO_K3D', value: 'true'
            }
            steps {
                sh 'helm lint provider-app/chart'
                sh 'docker tag provider-app:${IMAGE_TAG} ${REGISTRY}/provider-app:${IMAGE_TAG}'
                sh 'docker push ${REGISTRY}/provider-app:${IMAGE_TAG}'
                // Helm keeps this release in ConfigMaps: the deploy account may not read Secrets in provider,
                // where the provider app's key is.
                sh '''
                    HELM_DRIVER=configmap helm upgrade --install provider-app provider-app/chart --namespace provider \
                        --set image.repository=${REGISTRY}/provider-app --set-string image.tag=${IMAGE_TAG} \
                        --rollback-on-failure --timeout 5m
                '''
                sh 'bash scripts/wait-for-version.sh provider provider-app ${IMAGE_TAG}'
            }
            post {
                failure {
                    sh 'kubectl --namespace provider get pods || true'
                    sh 'kubectl --namespace provider get events --sort-by=.lastTimestamp | tail -n 20 || true'
                }
            }
        }

        stage('Smoke test: the provider app') {
            when {
                environment name: 'DEPLOY_TO_K3D', value: 'true'
            }
            steps {
                // As the provider app's customer: its pages, the bank's sign-in and decision pages, and back.
                sh '''
                    export PKI_DIR="$(mktemp -d)"
                    trap 'rm -rf "$PKI_DIR"' EXIT
                    bash scripts/smoke-test-files.sh "$PKI_DIR"
                    bash scripts/smoke-test.sh --provider-app https://k3d-bank-serverlb
                '''
            }
        }
    }

    post {
        success {
            archiveArtifacts artifacts: 'target/bank-open-api-*.jar, provider-app/target/provider-app-*.jar',
                             fingerprint: true
        }
    }
}
