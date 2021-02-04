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
az aks create --name $Connect_AKS_Name --resource-group $Connect_RG --location $Connect_Location --enable-cluster-autoscaler --min-count 3 --max-count 6 --node-count 3 --kubernetes-version $Connect_K8S_VER --no-ssh-key -s Standard_F4s_v2

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

cd $REPO_ROOT/perf/cluster/charts

# Install Kafka using the provided Kafka Helm chart
helm install kafka ./kafka -f ./kafka/values.yaml

# check that all kafka pods are up
kubectl get pods

```

Deploy Kafka Connect workers to setup the Connect cluster.

```bash

# Install Kafka Connect using the provided Kafka Connect Helm chart
helm install connect ./connect -f ./connect/values.yaml 

# check that all connect pods are up
kubectl get pods -l app=cp-kafka-connect

# Get the public IP of the Kafka Connect cluster
export CONNECT_PIP=$(kubectl get svc -l app=cp-kafka-connect -o jsonpath='{.items[0].status.loadBalancer.ingress[0].ip}')

# List the available connectors in the Connect cluster
curl -H "Content-Type: application/json" -X GET http://$CONNECT_PIP:8083/connectors

# Optional: Scale the Connect workers (eg: 3 workers) as needed for performance testing
helm upgrade connect ./connect -f ./connect/values.yaml --set replicaCount=3

```

Deploy Kafka Client to drive consistent traffic to the Kafka Connect workers.

```bash

cd $REPO_ROOT/perf/cluster/manifests

# Install Kafka perf client
kubectl apply -f kafka-client.yaml

```

## Observability

Monitor Kubernetes Cluster with Prometheus and Grafana using Helm.

- Validate and update Helm Chart repository in Helm Configuration:

```bash

helm repo update
helm repo list

```

- It is recommended to install the Prometheus operator in a separate namespace, as it is easy to manage. Create a new namespace called monitoring:

```bash

kubectl create ns monitoring

```

- Install Prometheus & Grafana in the monitoring namespace of the cluster:

```bash

helm install prometheus stable/prometheus --namespace monitoring
helm install grafana stable/grafana --namespace monitoring

# Validate pods running in namespace monitoring
kubectl --namespace monitoring get pods

```

- Get the Prometheus server URL to visit by running these commands:
  
```bash

export POD_NAME=$(kubectl get pods --namespace monitoring -l "app=prometheus,component=server" -o jsonpath="{.items[0].metadata.name}")
kubectl --namespace monitoring port-forward $POD_NAME 9090

```

- Get the Grafana URL to visit by running these commands:

```bash

export POD_NAME=$(kubectl get pods --namespace monitoring -l "app.kubernetes.io/name=grafana,app.kubernetes.io/instance=grafana" -o jsonpath="{.items[0].metadata.name}")
kubectl --namespace monitoring port-forward $POD_NAME 3000

```

Get the Grafana admin password:

```bash

kubectl get secret --namespace monitoring grafana -o jsonpath="{.data.admin-password}" | base64 --decode ; echo

```

- Login to Grafana by visting url `http://localhost:3000` and log in with the password obtained in previous step.

- Add Prometheus as Data Source in Grafana

- Import [Confluent Open Source Dashboard](https://grafana.com/grafana/dashboards/11773) in Grafana and configure `Prometheus` as Datasource.
