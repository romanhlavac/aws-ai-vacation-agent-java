#
# Nasazení - script pro automatizovaně (neinteraktivně) pomocí sam bez volby --guided
# předpoklad: unix-like shell (WSL, bash, ...)
#

# příprava prostředí - S3 bucket pro managed sam stack
REGION=us-east-2
ACC_ID=288761756328   # z tvojí caller identity (account pro rhlavac)
BUCKET="ai-vacation-agent-artifacts-$ACC_ID-$REGION"   # Navržené jméno S3 bucketu pro managed sam stack
ROLE_NAME=ai-vacation-agent-exec # Navržené jméno execution role
ROLE_ARN="arn:aws:iam::$ACC_ID:role/$ROLE_NAME" # Arn existující (již založené v AWS consoli) Execution Role (trust: lambda.amazonaws.com; práva: logs + bedrock invoke)
STACK=ai-vacation-agent-v2 # jméno nasazovaného AWS stacku pro aplikaci agenta

cd /mnt/c/users/rhlavac/IdeaProjects/aws-ai-vacation-agent-java # Změnit na adresář, kde to máte rozbalené !!!


# kontrola správně instalovaných balíků a CLI nástrojů (java, maven, aws, sam)
java -version # >21
mvn -v # > 3.9.11
aws --version # > 2.31.11
sam --version # > 1.144.0

# nastavení identity volajícího v lokálním CLI prostředí
aws configure # zadáš secret ID/KEY (které jsi získal v IAM) a default region
aws sts get-caller-identity # v Arn musí vrátit již nastaveného autorizovaného IAM uživatele (např. 'agent') s příslušnými oprávněními (viz template.yaml)

# založení S3 bucketu pro managed sam stack s příslušnými oprávněními (je to bezpečnější, než nechat si ho vytvořit samotným sam (IAM uživatel musí mít oprávnění, nebo přes admina v AWS consoli)
aws s3api create-bucket --bucket "$BUCKET"   --region "$REGION"   --create-bucket-configuration LocationConstraint="$REGION"
aws s3api put-public-access-block   --bucket "$BUCKET"   --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true

# úklid pred opakovaným nasazením
aws cloudformation list-stacks   --region "$REGION"   --query "StackSummaries[?contains(StackName, 'aws-sam-cli-managed')].[StackName,StackStatus]"   --output table
aws cloudformation delete-stack --region "$REGION" --stack-name aws-sam-cli-managed-default
aws s3 ls "s3://$BUCKET/$STACK/" --recursive || true
aws s3 rm "s3://$BUCKET/$STACK/" --recursive || true
FN=$(aws cloudformation describe-stack-resources \
  --stack-name "$STACK" --region "$REGION" \
  --query "StackResources[?LogicalResourceId=='VacationAgentFunction'].PhysicalResourceId" \
  --output text) # zjisti fyzické jméno funkce, pokud stack ještě existuje
[ -n "$FN" ] && aws logs delete-log-group --log-group-name "/aws/lambda/$FN" --region "$REGION" || true
aws cloudformation delete-stack --region "$REGION" --stack-name "$STACK"
aws cloudformation wait stack-delete-complete --stack-name "$STACK" --region "$REGION"


# nasazení app
sam build
sam validate # Validate SAM template
#sam deploy --guided   --stack-name "$STACK"  --region "$REGION"   --s3-bucket "$BUCKET"   --parameter-overrides BedrockModelId=amazon.nova-micro-v1:0
sam deploy # nasazení na AWS cloud, parametry předpřipravené v samconfig.toml
#sam sync --stack-name "$STACK" --watch   # Test Function in the Cloud
# rychlá kontrola app stacku (měl by být "CREATE_COMPLETE")
aws cloudformation describe-stacks \
  --stack-name "$STACK" --region "$REGION" \
  --query "Stacks[0].StackStatus"

# Test function
FN=$(aws cloudformation describe-stack-resources \
  --stack-name "$STACK" --region "$REGION" \
  --query "StackResources[?LogicalResourceId=='VacationAgentFunction'].PhysicalResourceId" \
  --output text)
aws lambda invoke --function-name "$FN" --region "$REGION" /tmp/out.json \
  --cli-binary-format raw-in-base64-out \
  --payload '{"resource":"/chat","path":"/chat","httpMethod":"POST","headers":{"Content-Type":"application/json"},"body":"{\"sessionId\":\"demo\",\"message\":\"Chci jet do Neratovic\"}","isBase64Encoded":false}'
cat /tmp/out.json; echo

# Test API
API_ID=$(aws cloudformation describe-stack-resources \
  --stack-name "$STACK" --region "$REGION" \
  --query "StackResources[?LogicalResourceId=='RestApi'].PhysicalResourceId" \
  --output text)
API_URL="https://${API_ID}.execute-api.${REGION}.amazonaws.com/Prod"
echo "TEST URL: $API_URL"
# 1) Bez destinace -> agent se zeptá
curl -s -X POST "$API_URL/chat" \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"demo1","message":""}' | jq .

# 2) S destinací explicitně:
curl -s -X POST "$API_URL/chat"   -H "Content-Type: application/json"   -d '{"sessionId":"demo1","destination":"Neratovice"}' | jq .

# 3) S destinací ve volném textu
curl -s -X POST "$API_URL/chat" \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"demo1","message":"Chci jet do hlavního města ČR"}' | jq .

# vytáhni si logy z nově založené log group pro lambda funkci
#aws logs tail "$FN" --since 10m --follow --region us-east-2




