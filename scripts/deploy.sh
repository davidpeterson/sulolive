#!/bin/bash -eu

script_dir="$(dirname $0)"
cd "$script_dir/.."

SHA1="$1"
DOCKER_IMAGE="$2"

# Deploy image to Docker Hub
docker push $DOCKER_IMAGE 

# Elastic Beanstalk vars
EB_BUCKET=eponai
EB_APP_NAME=jourmoney
EB_ENV_NAME=jourmoney-prod-env

# Create new Elastic Beanstalk version
DOCKERRUN_FILE=$SHA1-Dockerrun.aws.json
DOCKERRUN_S3_FILE=docker/$EB_APP_NAME/$EB_ENV_NAME/$DOCKERRUN_FILE

# Using comma (,) instead of slash (/) in sed because DOCKER_IMAGE contains slashes
sed "s,<DOCKER_IMAGE>,$DOCKER_IMAGE," < Dockerrun.aws.json.template > $DOCKERRUN_FILE
aws s3 cp $DOCKERRUN_FILE s3://$EB_BUCKET/$DOCKERRUN_S3_FILE
aws elasticbeanstalk create-application-version --application-name $EB_APP_NAME \
  --version-label $SHA1 --source-bundle S3Bucket=$EB_BUCKET,S3Key=$DOCKERRUN_S3_FILE

# Update Elastic Beanstalk environment to new version
aws elasticbeanstalk update-environment --environment-name $EB_ENV_NAME \
    --version-label $SHA1

