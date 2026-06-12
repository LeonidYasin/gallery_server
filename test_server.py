import subprocess
import json
import sys

URL = "http://127.0.0.1:8080/generate"
payload = {"prompt": "Привет! Ты работаешь через Ktor?"}

print("📡 1. Отправляем POST-запрос на Ktor-сервер...")
print("=============================================================")

curl_cmd = f"curl -X POST {URL} -H 'Content-Type: application/json' -d '{json.dumps(payload)}' --max-time 10 -s -w '\\n➡️ HTTP_STATUS: %{{http_code}}'"
res = subprocess.run(curl_cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

server_raw = res.stdout.split("➡️ HTTP_STATUS:")[0].strip()

if "➡️ HTTP_STATUS: 200" in res.stdout:
    print("✅ Сервер успешно ответил!")
    print("-------------------------------------------------------------")
    try:
        data = json.loads(server_raw)
        # Если в ответе есть ключ с ошибкой или деталями
        if "response" in data:
            print(f"📥 Ответ модели:\n{data['response']}")
        else:
            print(f"📥 Получен JSON:\n{json.dumps(data, ensure_ascii=False, indent=2)}")
    except Exception:
        print(f"📥 Сырой ответ сервера:\n{server_raw}")
    print("-------------------------------------------------------------")
else:
    print("❌ Ошибка: Сервер не отвечает или вернул некорректный статус-код.")
    if res.stderr:
        print(f"Детали ошибки curl: {res.stderr.strip()}")

print("=============================================================")
