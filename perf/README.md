# Deployment Walk Through

## Background

The following instructions allow the deployment of Kafka with Connect Workers in AKS.

### Azure Components in Use

- Azure Kubernetes Service
- Azure Cosmos DB

### Prerequisites

- Azure subscription with permissions to create:
  - Resource Groups, Service Principals, Cosmos DB, AKS, Azure Monitor
- Bash shell (tested on Mac, Ubuntu, Windows with WSL2)
  - Will not work in Cloud Shell or WSL1
- Azure CLI ([download](https://docs.microsoft.com/en-us/cli/azure/install-azure-cli?view=azure-cli-latest))
- Docker CLI ([download](https://docs.docker.com/install/))
- Visual Studio Code (optional) ([download](https://code.visualstudio.com/download))
- kubectl (install by using `sudo az aks install-cli`)
- Helm v3 ([Install Instructions](https://helm.sh/docs/intro/install/))

### Setup

Fork this repo and clone to your local machine

```bash

cd $HOME

git clone https://github.com/microsoft/kafka-connect-cosmosdb.git

```

Change into the base directory of the repo

```bash

cd kafka-connect-cosmosdb

export REPO_ROOT=$(pwd)

```

#### Login to Azure and select subscription

```bash

az login

# show your Azure accounts
az account list -o table

# select the Azure account
az account set -s {subscription name or Id}

```

This walkthrough will create resource groups and an Azure Kubernetes Service (AKS) cluster. An automation script is available which can be used instead of this walkthrough.

#### Choose a unique DNS name

```bash

# this will be the prefix for all resources
# do not include punctuation - only use a-z and 0-9
# must be at least 5 characters long
# must start with a-z (only lowercase)
export Connect_Name=[your unique name]

```

#### Create Resource Groups

> When experimenting with this sample, you should create new resource groups to avoid accidentally deleting resources
>
> If you use an existing resource group, please make sure to apply resource locks to avoid accidentally deleting resources

- You will create a resource group
  - One for AKS

```bash

# set location
export Connect_Location=eastus

# set application endpoint
# export Ngsa_App_Endpoint="${Ngsa_Name}.${Ngsa_Domain_Name}"

# resource group names
export Connect_RG="${Connect_Name}-cluster-rg"

# create the resource groups
az group create -n $Connect_RG -l $Connect_Location

```

#### Create the AKS Cluster

Set local variables to use in AKS deployment

```bash

export Connect_AKS_Name="${Connect_Name}-aks"

```

Determine the latest version of Kubernetes supported by AKS. It is recommended to choose the latest version not in preview for production purposes, otherwise choose the latest in the list.

```bash

az aks get-versions -l $Connect_Location -o table

export Connect_K8S_VER=1.19.3

```

Create and connect to the AKS cluster.

```bash

# this step usually takes 2-4 minutes
az aks create --name $Connect_AKS_Name --resource-group $Connect_RG --location $Connect_Location --enable-cluster-autoscaler --min-count 3 --max-count 6 --node-count 3 --kubernetes-version $Connect_K8S_VER --no-ssh-key

# note: if you see the following failure, navigate to your .azure\ directory
# and delete the file "aksServicePrincipal.json":
#    Waiting for AAD role to propagate[################################    ]  90.0000%Could not create a
#    role assignment for ACR. Are you an Owner on this subscription?

az aks get-credentials -n $Connect_AKS_Name -g $Connect_RG

kubectl get nodes

```

## Install Helm 3

Install the latest version of Helm by download the latest [release](https://github.com/helm/helm/releases):

```bash

# mac os
OS=darwin-amd64 && \
REL=v3.3.4 && \ #Should be lastest release from https://github.com/helm/helm/releases
mkdir -p $HOME/.helm/bin && \
curl -sSL "https://get.helm.sh/helm-${REL}-${OS}.tar.gz" | tar xvz && \
chmod +x ${OS}/helm && mv ${OS}/helm $HOME/.helm/bin/helm
rm -R ${OS}

```

or

```bash

# Linux/WSL
OS=linux-amd64 && \
REL=v3.3.4 && \
mkdir -p $HOME/.helm/bin && \
curl -sSL "https://get.helm.sh/helm-${REL}-${OS}.tar.gz" | tar xvz && \
chmod +x ${OS}/helm && mv ${OS}/helm $HOME/.helm/bin/helm
rm -R ${OS}

```

Add the helm binary to your path and set Helm home:

```bash

export PATH=$PATH:$HOME/.helm/bin
export HELM_HOME=$HOME/.helm

```

>NOTE: This will only set the helm command during the existing terminal session. Copy the 2 lines above to your bash or zsh profile so that the helm command can be run any time.

Verify the installation with:

```bash

helm version

```

Add the required helm repositories

```bash

helm repo add stable https://charts.helm.sh/stable
helm repo add confluentinc https://confluentinc.github.io/cp-helm-charts/
helm repo update

```

## Deploy Kafka and Kafka Connect with Helm

Kafka and Kafka Connect have been packed into Helm charts for deployment into the cluster. The following instructions will walk you through the manual process of deployment of the helm chart and is recommended for development and testing.

The `helm-config.yaml` file can be used as an override to the default values during the helm install.

```bash

cd $REPO_ROOT/perf/cluster/charts/

# Install NGSA using the upstream ngsa image from Dockerhub
helm install kafka ngsa -f ./ngsa/helm-config.yaml

# check that all pods are up
kubectl get pods

```

Check that the test certificates have been issued. You can check in the browser, or use curl. With the test certificates, it is expected that you get a privacy error.

```bash

export Ngsa_Https_App_Endpoint="https://${Ngsa_App_Endpoint}"

# Curl the https endpoint. You should see a certificate problem. This is expected with the staging certificates from Let's Encrypt.
curl $Ngsa_Https_App_Endpoint

```

After verifying that the test certs were issued, update the deployment to use the "letsencrypt-prod" ClusterIssuer to get valid certs from the Let's Encrypt production environment.

```bash

helm upgrade ngsa-aks ngsa -f ./ngsa/helm-config.yaml  --namespace ngsa --set cert.issuer=letsencrypt-prod

```

Run the Validation Test

> For more information on the validation test tool, see [Lode Runner](../../src/Ngsa.LodeRunner).

```bash

# run the tests in a container
docker run -it --rm retaildevcrew/loderunner:beta --server $Ngsa_Https_App_Endpoint --files baseline.json

```

## Smoke Tests

Deploy Loderunner to drive consistent traffic to the AKS cluster for monitoring.

```bash

cd $REPO_ROOT/IaC/AKS/cluster/charts

kubectl create namespace ngsa-l8r

cp ./loderunner/helm-config.example.yaml ./loderunner/helm-config.yaml

helm install l8r loderunner -f ./loderunner/helm-config.yaml --namespace ngsa-l8r

# Verify the pods are running
kubectl get pods --namespace ngsa-l8r

```

## Observability

Observability is enabled through a combination of Fluent Bit to forward logs to Azure Log Analytics and queries directly to Log Analytics or via Azure Dashboards.

### Fluent Bit Log Forwarding

Deploy Fluent Bit to forward application and smoker logs to the Log Analytics instance.

```bash

cd $REPO_ROOT/IaC/AKS/cluster/charts

kubectl create namespace fluentbit

kubectl create secret generic fluentbit-secrets \
  --namespace fluentbit \
  --from-literal=WorkspaceId=$(az monitor log-analytics workspace show -g $Ngsa_Log_Analytics_RG -n $Ngsa_Log_Analytics_Name --query customerId -o tsv) \
  --from-literal=SharedKey=$(az monitor log-analytics workspace get-shared-keys -g $Ngsa_Log_Analytics_RG -n $Ngsa_Log_Analytics_Name --query primarySharedKey -o tsv)

helm install fluentbit fluentbit --namespace fluentbit

# Verify the fluentbit pod is running
kubectl get pod --namespace fluentbit

```

### Querying Log Analytics

Navigate to the Log Analytics resource in the Azure portal and go to General -> Logs to explore the logs with KQL queries.

Sample queries:

```bash

# View the latest logs from the data service

ngsa_CL
| where k_container == "ds"
   and LogName_s == "Ngsa.RequestLog"
| project TimeGenerated, CosmosName_s, Zone_s, CVector_s, Duration_d, StatusCode_d, Path_s

# Calculate the 75th and 95th percentiles for the ngsa app response time and compare by app type (in-memory or cosmos) and zone  

ngsa_CL
| where k_container == "app"
   and k_app in ('ngsa-cosmos','ngsa-memory')
   and LogName_s == "Ngsa.RequestLog"
| summarize percentile(Duration_d, 75), percentile(Duration_d, 95) by Zone_s, k_app
| extend Zone=Zone_s, 75th=round(percentile_Duration_d_75,2), 95th=round(percentile_Duration_d_95,2), AppType=k_app
| project AppType, Zone, 75th, 95th
| order by AppType, Zone asc

```

## AKS Cluster using automated script

With this script a cluster can be deployed in AKS (uses the same steps above).
The script is self-contained, meaning, it won't change the user-environment (e.g. selected Azure Subscription or ubernetes context) unless it's explicitly specified.
It is located [here](./scripts/create-cluster-env.bash).
Script Usage:

```bash
    ./create-cluster-env.bash --ngsa-prefix basename123 [Optional Args/Flags]
    ./create-cluster-env.bash -s azure-subs -n basename123 [Optional Args/Flags]

Required args:
    -n | --ngsa-prefix BASE_NAME    This will be the NGSA prefix for all resources
                                    Do not include punctuation - only use a-z and 0-9
                                    must be at least 5 characters long
                                    must start with a-z (only lowercase)
Optional args:
    -s | --subscription AZ_SUB      Azure Subscription Name or ID
    -e | --env ENVIRONMENT          Environemnt Type. Default: dev (See README.md for other values)
    -d | --domain DOMAIN_NAME       Registered Domain Name. Default: nip.io (Requires --email)
    -m | --email EMAIL_DOMAIN       Required Email if a '--domain' is given
    -l | --location LOCATION        Location where the resources will be created. Default: westus2
    -k | --k8s-ver K8S_VERSION      Kubernetes version used. Default: 1.18.8
                                    Use 'az aks get-versions -l westus2 -o table' to get supported versions
    -c | --node-count NODE_COUNT    Cluster Node Count. Default: 3
    -r | --dns-rg DNS_RG            DNS Resource group name. Default: dns-rg
    -i | --cosmos-key COSMOS_KEY
    -u | --cosmos-url COSMOS_URL    In case users want to use their own CosmosDBBoth Key and URL are empty by default.
Optional Flag:
    -x | --set-k8s-context          Sets the kubernetes context for current user in /home/kushal/.kube/config
    -h | --help                     Show the usage
```

Example usage:

- Create a cluster with selected Azure subscription

  `./create-cluster-env.bash --ngsa-prefix basengsa`
- Create a cluster with specific Azure subscription

  `./create-cluster-env.bash -s azure-subscription-name -n basengsa`
- Create a cluster in a specific location

  `./create-cluster-env.bash -n basengsa -l centralus`
- Create a cluster and set the current k8s context

  `./create-cluster-env.bash --subscription "az-sub" -n basengsa --set-k8s-context`
- Create a cluster with specific environmen type

  `./create-cluster-env.bash --subscription "az-sub" -n ultrangsa -d abcd.efg --email user@email.org --env stage`
- Create a cluster with specific domain name

  `./create-cluster-env.bash --subscription "az-sub" -n basengsa -d abcd.efg --email user@email.org`
- Create a cluster with existing CosmosDB

  `./create-cluster-env.bash -s az-sub -n ngsatest -d abcd.ms -l centralus -i AkI=FAKE=KEY=oGk=SOME=FAKE=KEY=Zh7Iad703gWwBb0P=YET=ANOTHER=FAKE=KEY=w0Zubg== -u https://sample-cosmos-db.documents.zure.com:443/`
- Create a cluster with specific node count

  `./create-cluster-env.bash --subscription "az-sub" -n basengsa -c 6 -x`
