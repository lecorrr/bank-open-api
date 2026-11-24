// CI for bank-open-api: compile, run every test, publish reports, build the image. With the k3d cluster
// (DEPLOY_TO_K3D=true, see docs/cluster.md) it deploys the image and, signed in as the test provider, takes a
// consent through the API via Traefik.
pipeline {
    agent any

    options {
        timeout(time: 30, unit: 'MINUTES')
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

        stage('Docker image') {
            steps {
                script {
                    // The commit names the image, so the cluster always shows which code runs.
                    env.IMAGE_TAG = sh(script: 'git rev-parse --short=12 HEAD', returnStdout: true).trim()
                }
                sh 'docker build -t bank-open-api:${IMAGE_TAG} -t bank-open-api:latest .'
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
                // The way a provider comes in: k3d's load balancer, Traefik's mutual TLS, the Gateway, the route.
                // Jenkins is on k3d's Docker network, where the load balancer listens on port 443. The test
                // provider's key and certificate, the bank's test CA and the test customer's password come from
                // the cluster's Secrets into a folder only this build sees.
                sh '''
                    export PKI_DIR="$(mktemp -d)"
                    trap 'rm -rf "$PKI_DIR"' EXIT
                    kubectl --namespace bank get secret provider-ca -o jsonpath='{.data.ca\\.crt}' \
                        | base64 -d > "$PKI_DIR/ca.crt"
                    kubectl --namespace bank get secret smoke-test-provider -o jsonpath='{.data.tls\\.crt}' \
                        | base64 -d > "$PKI_DIR/smoke-test.crt"
                    kubectl --namespace bank get secret smoke-test-provider -o jsonpath='{.data.tls\\.key}' \
                        | base64 -d > "$PKI_DIR/smoke-test.key"
                    kubectl --namespace bank get secret test-customer -o jsonpath='{.data.password}' \
                        | base64 -d > "$PKI_DIR/test-customer.password"
                    bash scripts/smoke-test.sh https://k3d-bank-serverlb
                '''
            }
        }
    }

    post {
        success {
            archiveArtifacts artifacts: 'target/bank-open-api-*.jar', fingerprint: true
        }
    }
}
