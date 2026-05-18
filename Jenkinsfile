pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
    }

    environment {
        APP_NAME = 'spring-api'

        // Replace with your real JFrog registry
        JFROG_REGISTRY = 'acme.jfrog.io'
        JFROG_REPO = 'docker-local'

        AWS_DEFAULT_REGION = 'ap-south-1'

        // Same repo GitOps path
        HELM_VALUES_FILE = 'helm/spring-api/values-prod.yaml'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Detect Helm Only Change') {
            steps {
                sh '''
                    set -e

                    if git rev-parse HEAD~1 >/dev/null 2>&1; then
                      CHANGED_FILES=$(git diff --name-only HEAD~1 HEAD)
                    else
                      CHANGED_FILES=$(git ls-files)
                    fi

                    echo "Changed files:"
                    echo "$CHANGED_FILES"

                    NON_HELM_FILES=$(echo "$CHANGED_FILES" | grep -v '^helm/' | grep -v '^argocd/' || true)

                    if [ -z "$NON_HELM_FILES" ]; then
                      echo "Only helm/argocd files changed. Skipping image build."
                      echo "SKIP_BUILD=true" > pipeline.env
                    else
                      echo "Application or pipeline files changed. Continue build."
                      echo "SKIP_BUILD=false" > pipeline.env
                    fi
                '''
            }
        }

        stage('Test') {
            steps {
                sh '''
                    set -e
                    . ./pipeline.env

                    if [ "$SKIP_BUILD" = "true" ]; then
                      echo "Skipping tests because only Helm/ArgoCD changed."
                      exit 0
                    fi

                    if [ -f ./mvnw ]; then
                      ./mvnw -B clean test
                    else
                      mvn -B clean test
                    fi
                '''
            }
        }

        stage('Package') {
            steps {
                sh '''
                    set -e
                    . ./pipeline.env

                    if [ "$SKIP_BUILD" = "true" ]; then
                      echo "Skipping package."
                      exit 0
                    fi

                    if [ -f ./mvnw ]; then
                      ./mvnw -B -DskipTests package
                    else
                      mvn -B -DskipTests package
                    fi
                '''
            }
        }

        stage('Build Image') {
            steps {
                sh '''
                    set -euo pipefail
                    . ./pipeline.env

                    if [ "$SKIP_BUILD" = "true" ]; then
                      echo "Skipping docker build."
                      exit 0
                    fi

                    SHORT_SHA=$(git rev-parse --short=8 HEAD)
                    IMAGE_TAG="${BUILD_NUMBER}-${SHORT_SHA}"

                    echo "IMAGE_TAG=${IMAGE_TAG}" >> pipeline.env
                    echo "IMAGE_REPOSITORY=${JFROG_REGISTRY}/${JFROG_REPO}/${APP_NAME}" >> pipeline.env

                    docker build --pull -t ${JFROG_REGISTRY}/${JFROG_REPO}/${APP_NAME}:${IMAGE_TAG} .
                '''
            }
        }

        stage('Push Image to JFrog') {
            steps {
                withCredentials([[
                    $class: 'AmazonWebServicesCredentialsBinding',
                    credentialsId: 'aws-platform-creds'
                ]]) {
                    sh '''
                        set -euo pipefail
                        . ./pipeline.env

                        if [ "$SKIP_BUILD" = "true" ]; then
                          echo "Skipping docker push."
                          exit 0
                        fi

                        SECRET_JSON=$(aws secretsmanager get-secret-value \
                          --secret-id cicd/jfrog/docker-push \
                          --query SecretString \
                          --output text)

                        JFROG_USER=$(echo "${SECRET_JSON}" | jq -r '.username')
                        JFROG_TOKEN=$(echo "${SECRET_JSON}" | jq -r '.token')

                        echo "${JFROG_TOKEN}" | docker login "${JFROG_REGISTRY}" \
                          --username "${JFROG_USER}" \
                          --password-stdin

                        docker push ${JFROG_REGISTRY}/${JFROG_REPO}/${APP_NAME}:${IMAGE_TAG}
                    '''
                }
            }
        }

        stage('Update Helm Image Tag in Same Repo') {
            steps {
                withCredentials([sshUserPrivateKey(
                    credentialsId: 'backend-repo-ssh',
                    keyFileVariable: 'SSH_KEY'
                )]) {
                    sh '''
                        set -euo pipefail
                        . ./pipeline.env

                        if [ "$SKIP_BUILD" = "true" ]; then
                          echo "Skipping helm values update."
                          exit 0
                        fi

                        yq -i '.image.repository = strenv(IMAGE_REPOSITORY)' ${HELM_VALUES_FILE}
                        yq -i '.image.tag = strenv(IMAGE_TAG)' ${HELM_VALUES_FILE}

                        git config user.name "jenkins-bot"
                        git config user.email "jenkins-bot@company.local"

                        git add ${HELM_VALUES_FILE}

                        if ! git diff --cached --quiet; then
                          git commit -m "deploy: ${APP_NAME} ${IMAGE_TAG}"
                          export GIT_SSH_COMMAND="ssh -i ${SSH_KEY} -o StrictHostKeyChecking=no"
                          git push origin HEAD:main
                        else
                          echo "No Helm values change detected."
                        fi
                    '''
                }
            }
        }
    }
}
