pipeline {
    agent {
        label 'docker-host'
    }
    options {
        disableConcurrentBuilds()
        disableResume()
    }

    parameters {
        string name: 'ENVIRONMENT_NAME', trim: true     
        password defaultValue: '', description: 'Password to use for MySQL container - root user', name: 'DB_PASSWORD'
        string name: 'DB_PORT', trim: true  
        
        string name: 'RDBMS', defaultValue: 'MySQL', description: 'choose a RDBMS (MySQL, PostgreSQL)', trim: true
        
        booleanParam(name: 'SKIP_STEP_1', defaultValue: false, description: 'STEP 1 - RE-CREATE DOCKER IMAGE')
    }
  
    stages {
        stage('Validate DB_PORT') {
            steps {
                script{
                    def port = params.DB_PORT
                    if (!port.isNumber() || !(port.toInteger() >= 1024 && port.toInteger() <= 65535)) {
                       error("Error: DB_PORT must be a number between 1024 and 65535.")
                    } else {
                       println "Port is valid."
                    }
                }
            }
        }
        stage('Checkout GIT repository') {
            steps {     
              script {
                git branch: 'dev',
                credentialsId: "${env.credentialsId}",
                url: 'git@github.com:lcosta/jenkins.git'
              }
            }
        }
        stage('Create latest Docker image') {
            steps {     
              script {
                if (!params.SKIP_STEP_1){    
                    echo "Creating docker image with name $params.ENVIRONMENT_NAME using port: $params.DB_PORT"
                    
                    if("mysql" == params.RDBMS.toLowerCase()){
                      sh """
                      sed 's/<PASSWORD>/${params.DB_PASSWORD}/g' pipelines/include/create_developer.template > pipelines/include/create_developer.sql
                      """

                      sh """
                      docker build pipelines/ -t $params.ENVIRONMENT_NAME:latest
                      """
                    }
                    else if("postgresql" == params.RDBMS.toLowerCase()){
                      sh """
                      sed 's/<PASSWORD>/${params.DB_PASSWORD}/g' pipelines/PostgreSQL/include/create_developer.template > pipelines/PostgreSQL/include/create_developer.sql
                      """

                      sh """
                      docker build pipelines/PostgreSQL/ -t $params.ENVIRONMENT_NAME:latest 
                      """
                    } else {
                      error("Error: choose a valid RDBMS '$params.RDBMS'.")
                    }

                }else{
                    echo "Skipping STEP1"
                }
              }
            }
        }
        stage('Start new container using latest image and create user') {
            steps {     
              script {
                
                def dateTime = (sh(script: "date +%Y%m%d%H%M%S", returnStdout: true).trim())
                def containerName = "${params.ENVIRONMENT_NAME}_${dateTime}"

                sh """
                containers=\$(docker container ls --filter "expose=$params.DB_PORT" --format "{{.ID}}")
                if [ -n "\$containers" ]; then
                  echo "Stopping containers:"
                  echo "\$containers"
                  echo "\$containers" | xargs docker stop
                else
                  echo "No containers found with port $params.DB_PORT exposed"
                fi
                """
                
                if("mysql" == params.RDBMS.toLowerCase()){
                  sh """
                  docker run -itd --name ${containerName} --rm -e MYSQL_ROOT_PASSWORD=$params.DB_PASSWORD -p $params.DB_PORT:3306 $params.ENVIRONMENT_NAME:latest
                  """

                  sh """
                  while ! docker exec ${containerName} /bin/bash -c 'mysql --user="root" --password="$params.DB_PASSWORD" -e "SELECT 1"' >/dev/null 2>&1; do
                      sleep 1
                  done
                  """
                  
                  sh """
                  docker exec ${containerName} /bin/bash -c 'mysql --user="root" --password="$params.DB_PASSWORD" < /scripts/create_developer.sql'
                  """
                }
                else if("postgresql" == params.RDBMS.toLowerCase()){
                  sh """
                  docker run -itd --name ${containerName} --rm -e POSTGRES_PASSWORD=$params.DB_PASSWORD -p $params.DB_PORT:5432 $params.ENVIRONMENT_NAME:latest
                  """

                  sh """
                  while ! docker exec ${containerName} /bin/bash -c 'PGPASSWORD="$params.DB_PASSWORD" psql -U="root" -c "SELECT 1"' >/dev/null 2>&1; do
                      sleep 1
                  done
                  """
                } else {
                  error("Error: choose a valid RDBMS '$params.RDBMS'.")
                }





                echo "Docker container created: $containerName"

              }
            }
        }
    }

}
