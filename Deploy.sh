#
# Nasazení - script pro automatizovaně (neinteraktivně) pomocí sam bez volby --guided
# předpoklad: unix-like shell (WSL, bash, ...)
#
cd /mnt/c/users/rhlavac/IdeaProjects/aws-ai-vacation-agent-java # Změnit na adresář, kde to máte rozbalené !!!

# kontrola správně instalovaných balíků a CLI nástrojů (java, maven, aws, sam)
java -version # >21
mvn -v # > 3.9.11
sam --version # > 1.144.0
aws sts get-caller-identity # v Arn musí vrátit již nastaveného autorizovaného IAM uživatele (např. 'agent') s příslušnými oprávněními (viz template.yaml)

# příprava prostředí - S3 bucket pro managed sam stack
REGION=us-east-2
ACC_ID=288761756328   # z tvojí caller identity
BUCKET="ai-vacation-agent-artifacts-$ACC_ID-$REGION"   # Navržené jméno S3 bucketu pro managed sam stack
ROLE_NAME=ai-vacation-agent-exec # Navržené jméno execution role
ROLE_ARN="arn:aws:iam::$ACC_ID:role/$ROLE_NAME" # Arn existující (již založené v AWS consoli) Execution Role (trust: lambda.amazonaws.com; práva: logs + bedrock invoke)
STACK=ai-vacation-agent-v2 # jméno nasazovaného AWS stacku

# založení S3 bucketu pro managed sam stack s příslušnými oprávněními (je to bezpečnější, než nechat si ho vytvořit samotným sam (IAM uživatel musí mít oprávnění, nebo přes admina v AWS consoli)
aws s3api create-bucket --bucket "$BUCKET"   --region "$REGION"   --create-bucket-configuration LocationConstraint="$REGION"
aws s3api put-public-access-block   --bucket "$BUCKET"   --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true

# úklid pred opakovaným nasazením
aws cloudformation list-stacks   --region "$REGION"   --query "StackSummaries[?contains(StackName, 'aws-sam-cli-managed')].[StackName,StackStatus]"   --output table
aws cloudformation delete-stack --region "$REGION" --stack-name aws-sam-cli-managed-default
aws s3 ls "s3://$BUCKET/$STACK/" --recursive || true
aws s3 rm "s3://$BUCKET/$STACK/" --recursive || true
FN="/aws/lambda/$(aws cloudformation describe-stack-resources \
  --stack-name "$STACK" --region "$REGION" \
  --query "StackResources[?LogicalResourceId=='VacationAgentFunction'].PhysicalResourceId" \
  --output text 2>/dev/null)" # zjisti fyzické jméno funkce, pokud stack ještě existuje
[ -n "$FN" ] && aws logs delete-log-group --log-group-name "$FN" --region "$REGION" || true
aws cloudformation delete-stack --region "$REGION" --stack-name "$STACK"
aws cloudformation wait stack-delete-complete --stack-name "$STACK" --region "$REGION"


# nasazení app
#sam deploy --guided   --stack-name "$STACK"  --region "$REGION"   --s3-bucket "$BUCKET"   --parameter-overrides BedrockModelId=amazon.nova-micro-v1:0
sam deploy #parametry předpřipravené v samconfig.toml

# rychlá kontrola app stacku
aws cloudformation describe-stacks \
  --stack-name "$STACK" --region "$REGION" \
  --query "Stacks[0].StackStatus"

# Test API
API_ID="izdvmnifhf" # Změnit na ID v sam deploy logu/reportu !!!
API_URL="https://${API_ID}.execute-api.${REGION}.amazonaws.com/Prod"
# 1) Bez destinace -> agent se zeptá
curl -s -X POST "$API_URL/chat" \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"demo1","message":""}' | jq .

# 2) S destinací v textu
curl -s -X POST "$API_URL/chat" \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"demo1","message":"Chci jet do Neratovic"}' | jq .

# 3) Nebo explicitně:
curl -s -X POST "$API_URL/chat"   -H "Content-Type: application/json"   -d '{"sessionId":"demo1","destination":"Prague"}' | jq .






