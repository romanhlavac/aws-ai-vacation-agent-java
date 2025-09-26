# AWS AI Vacation Agent (Java + Lambda + API Gateway + Bedrock)

Jednoduchý AI agent nad AWS, který se nejprve zeptá, *kam chcete jet na dovolenou*, a následně
doplní **předpověď počasí** pro vybranou destinaci (7 dní, Open‑Meteo).

- **BE**: Java 21 (AWS Lambda), Amazon API Gateway, Amazon Bedrock (LLM – přes `InvokeModel`), Open‑Meteo (bez API klíče).
- **Infra**: AWS SAM (`template.yaml`).
- **IDE**: IntelliJ IDEA (Maven projekt).

---

## Rychlý start

### 0) Předpoklady
- AWS účet, `aws configure` hotové (role s oprávněními pro Lambda, API Gateway, CloudFormation, Logs, a **bedrock:InvokeModel**).
- Povolené použití vybraného modelu v **Amazon Bedrock** (v konzoli Bedrocku -> *Model access* povolit Anthropic/Meta apod.).
- Nainstalováno: **Java 21**, **Maven 3.9+**, **AWS SAM CLI**.
- Region: doporučeně `us-east-1` (Bedrock má největší pokrytí).

### 1) Stažení projektu
Stáhněte ZIP z odkazu níže a rozbalte jej. Otevřete v IntelliJ IDEA jako **Maven** projekt.

### 2) Vyberte Bedrock model
V souboru **`template.yaml`** je parametr `BedrockModelId` (výchozí je prázdný). V konzoli Bedrocku si
zjistěte přesné ID modelu, např. pro Anthropic Claude Sonnet může vypadat podobně jako:

```
anthropic.claude-3-5-sonnet-20240620-v1:0 nebo amazon.nova-micro-v1:0 (nejlevnější použití pro agenty)
```
> Pozn.: Název modelu se v čase mění. Vždy použijte to, které vidíte ve své konzoli v daném regionu.

### 3) Build & Deploy (SAM)

```bash
# v kořeni projektu
sam build

# první nasazení s průvodcem (zvolte např. region us-east-1)
sam deploy --guided
```

A. Při `--guided` vyplňte:
- **Stack name**: `ai-vacation-agent`
- **AWS Region**: např. `us-east-1`
- **Parameter BedrockModelId**: vložte přesné ID modelu z Bedrocku
- Ostatní volby můžete ponechat na výchozích hodnotách (SAM si zapamatuje pro příště `samconfig.toml`).

B. Bez `--guided`:
- Viz Deploy.sh

Po dokončení uvidíte **API endpoint** (HTTP URL).

### 4) Test (curl)

1) První dotaz bez destinace → agent se zeptá:
```bash
curl -s -X POST "$API_URL/chat"   -H "Content-Type: application/json"   -d '{"sessionId":"demo1","message":""}' | jq .
```

2) Dotaz s destinací v textu:
```bash
curl -s -X POST "$API_URL/chat"   -H "Content-Type: application/json"   -d '{"sessionId":"demo1","message":"Chci jet do Neratovic"}' | jq .
```

3) Nebo explicitně:
```bash
curl -s -X POST "$API_URL/chat"   -H "Content-Type: application/json"   -d '{"sessionId":"demo1","destination":"Prague"}' | jq .
```

### 5) Úklid
```bash
sam delete
```

---

## Co to dělá?

Endpoint **POST `/chat`** přijme JSON:
```json
{
  "sessionId": "string",
  "message": "volitelně text",
  "destination": "volitelně název města/místa"
}
```

- Pokud **není** k dispozici `destination`, backend použije **Bedrock (LLM)**, aby ji
  z uživatelského textu vytáhl. Když ji nezíská, vrátí otázku: *„Kam chceš jet na dovolenou?“*.
- Pokud destinace **je** k dispozici, backend zavolá **Open‑Meteo Geocoding** (získá lat/lon),
  následně **Open‑Meteo Forecast** a vrátí souhrn počasí na 7 dní v češtině.

> Pozn.: Není nutná žádná DB. „Sezení“ je na klientovi (`sessionId` je jen echo).

---

## Struktura

```
aws-ai-vacation-agent-java/
├─ template.yaml                  # SAM šablona (Lambda + API Gateway + oprávnění)
├─ pom.xml                        # Maven (shade + závislosti)
├─ README.md                      # tento návod
└─ src/
   └─ main/
      └─ java/com/example/agent/
         ├─ AgentHandler.java
         ├─ BedrockClientFactory.java
         ├─ HttpUtil.java
         └─ Models.java
```

---

## Poznámky k nákladům a bezpečnosti
- Volání Bedrocku a běh Lambdy jsou zpoplatněné dle ceníku AWS. Open‑Meteo je zdarma.
- Omezte timeouty, logujte přiměřeně, chraňte endpoint (např. API keys, WAF), a podle
  potřeby přidejte validaci vstupů či guardrails v promptu.

Hodně štěstí! 🎒🌍
