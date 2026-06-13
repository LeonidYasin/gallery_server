#!/usr/bin/env python3
import os
import sys

# Определяем правильный путь к Download
download_dir = "/storage/emulated/0/Download"

# Альтернативные пути (на случай, если symlink не работает)
if not os.path.exists(download_dir):
    download_dir = "/sdcard/Download"

def read_file(filename, title):
    filepath = os.path.join(download_dir, filename)
    if os.path.exists(filepath):
        print(f"\n📄 {title}:")
        print("=" * 60)
        with open(filepath, 'r') as f:
            print(f.read())
        print("=" * 60)
        print(f"📍 Путь: {filepath}")
    else:
        print(f"\n⚠️ Файл не найден: {filepath}")

if __name__ == "__main__":
    print(f"📁 Использую директорию: {download_dir}")
    read_file("last_request.txt", "ПОСЛЕДНИЙ ЗАПРОС")
    read_file("last_response.txt", "ПОСЛЕДНИЙ ОТВЕТ")
    
    # Показываем список всех .txt файлов в Download
    print("\n📋 Все текстовые файлы в Download:")
    print("-" * 40)
    for f in os.listdir(download_dir):
        if f.endswith('.txt'):
            size = os.path.getsize(os.path.join(download_dir, f))
            print(f"  • {f} ({size} байт)")
