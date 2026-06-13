#!/usr/bin/env python3
import requests
import json
import sys
import time

def send_query(prompt, host="localhost", port=8080):
    url = f"http://{host}:{port}/generate"
    
    print(f"📤 Отправка запроса к {url}")
    print(f"📝 Текст запроса:\n{prompt}\n")
    
    try:
        start_time = time.time()
        response = requests.post(
            url,
            json={"prompt": prompt},
            headers={"Content-Type": "application/json"},
            timeout=120
        )
        elapsed = time.time() - start_time
        
        print(f"✅ Статус ответа: {response.status_code}")
        print(f"⏱️ Время ответа: {elapsed:.2f} секунд")
        
        if response.status_code == 200:
            data = response.json()
            if "response" in data:
                print("\n🤖 ОТВЕТ МОДЕЛИ:")
                print("=" * 60)
                print(data["response"])
                print("=" * 60)
            elif "error" in data:
                print("\n❌ ОШИБКА:")
                print("=" * 60)
                print(data["error"])
                print("=" * 60)
            else:
                print(f"\n📦 Полный ответ: {json.dumps(data, indent=2, ensure_ascii=False)}")
        else:
            print(f"❌ Ошибка HTTP: {response.status_code}")
            print(response.text)
            
    except requests.exceptions.ConnectionError:
        print("❌ Не удалось подключиться к серверу. Убедитесь, что приложение запущено и сервер активен.")
    except requests.exceptions.Timeout:
        print("❌ Таймаут ожидания ответа от сервера (120 секунд)")
    except Exception as e:
        print(f"❌ Ошибка: {e}")

if __name__ == "__main__":
    if len(sys.argv) > 1:
        query = " ".join(sys.argv[1:])
    else:
        query = input("Введите запрос для модели: ")
    
    send_query(query)
