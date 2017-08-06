#!/bin/bash


ACTION=$1
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
MYIP=`dig +short myip.opendns.com @resolver1.opendns.com`
AWS_ACCOUNT_NUM=`aws ec2 describe-security-groups \
                    --group-names 'Default' \
                    --query 'SecurityGroups[0].OwnerId' \
                    --output text`

function create {
    echo ">>> Deploying stacks..."
     aws cloudformation create-stack --stack-name kafka --template-body file://deploy/cf-kafka.yml \
        --parameters ParameterKey=KeyName,ParameterValue=gen_key_pair ParameterKey=VPCId,ParameterValue=vpc-1491216d \
        ParameterKey=SubnetID,ParameterValue=subnet-0c219220 ParameterKey=RemoteAccessCIDR,ParameterValue=${MYIP}/32 \
        --capabilities CAPABILITY_IAM

    aws cloudformation create-stack  --stack-name redshift --template-body file://deploy/cf-redshift.yml \
        --parameters ParameterKey=KeyName,ParameterValue=gen_key_pair ParameterKey=MasterUserPassword,ParameterValue=MyPassword123 \
        ParameterKey=VPCId,ParameterValue=vpc-1491216d ParameterKey=SubnetID,ParameterValue=subnet-0c219220 \
        ParameterKey=SubnetCIDR,ParameterValue=172.31.64.0/24

}

function delete {
    echo ">>> Deleting stacks..."
    aws cloudformation delete-stack --stack-name kafka
    aws cloudformation delete-stack --stack-name redshift

}

if [[ ${ACTION} == "create" ]]; then
    create

elif [[ ${ACTION} == "delete" ]]; then
    delete
else
    echo "argument error; please supply one of the following arguments: ['create', 'delete']"
fi
