#!/usr/bin/env python3
import os
import sys

def read_file(path, title):
    if os.path.exists(path):
        print(f"\n📄 {title}:")
        print("=" * 60)
        with open(path, 'r') as f:
            print(f.read())
        print("=" * 60)
    else:
        print(f"\n⚠️ Файл не найден: {path}")

if __name__ == "__main__":
    read_file("/sdcard/Download/last_request.txt", "ПОСЛЕДНИЙ ЗАПРОС")
    read_file("/sdcard/Download/last_response.txt", "ПОСЛЕДНИЙ ОТВЕТ")
