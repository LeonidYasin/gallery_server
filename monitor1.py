#!/usr/bin/env python3
import subprocess
import time
import json
import re
import os
import sys

def run_cmd(cmd, capture=True):
    result = subprocess.run(cmd, shell=True, capture_output=capture, text=True)
    return result.stdout.strip() if capture else result

def get_latest_run():
    """Получить ID последнего запуска workflow"""
    result = run_cmd("gh run list --limit 1 --json databaseId,status,conclusion --jq '.[0]'")
    if result:
        return json.loads(result)
    return None

def get_run_logs(run_id):
    """Получить логи сборки и отфильтровать только ошибки"""
    print(f"\n📥 Получаем логи сборки #{run_id}...")
    
    # Сохраняем полный лог во временный файл
    log_file = f"/tmp/build_log_{run_id}.txt"
    run_cmd(f"gh run view {run_id} --log > {log_file}", capture=False)
    
    # Ищем ошибки компиляции Kotlin
    errors = []
    with open(log_file, 'r') as f:
        content = f.read()
        
        # Ищем строки с ошибками компиляции
        patterns = [
            r'e: .*\.kt:.*\n.*\n.*\^',
            r'error: .*',
            r'FAILURE: Build failed with an exception\.',
            r'What went wrong:',
            r'Execution failed for task',
            r'> Task :app:compileDebugKotlin FAILED',
            r'unresolved reference',
            r'Type mismatch',
            r'Cannot infer type',
            r'No value passed for parameter',
            r'Return type mismatch',
            r'required: .* found: .*'
        ]
        
        for pattern in patterns:
            matches = re.findall(pattern, content, re.MULTILINE)
            if matches:
                errors.extend(matches)
    
    # Если нашли ошибки - показываем их
    if errors:
        print("\n" + "="*60)
        print("🔴 НАЙДЕНЫ ОШИБКИ КОМПИЛЯЦИИ:")
        print("="*60)
        for err in errors[:20]:  # Первые 20 ошибок
            print(err)
            print("-"*40)
    else:
        # Показываем последние 50 строк лога
        print("\n" + "="*60)
        print("⚠️  НЕ УДАЛОСЬ РАСПОЗНАТЬ ОШИБКИ, ПОКАЗЫВАЮ ПОСЛЕДНИЕ 50 СТРОК:")
        print("="*60)
        lines = content.split('\n')
        for line in lines[-50:]:
            print(line)
    
    # Очистка
    os.remove(log_file)
    
    # Возвращаем ошибки для дальнейшего анализа
    return errors

def monitor_build():
    print("🚀 Мониторинг сборки GitHub Actions...")
    
    while True:
        run = get_latest_run()
        if not run:
            print("❌ Не удалось получить информацию о сборке")
            return False
        
        status = run.get('status', '')
        conclusion = run.get('conclusion', '')
        run_id = run.get('databaseId')
        
        print(f"\n📊 Статус: {status}, Результат: {conclusion if status == 'completed' else 'ожидание'}")
        
        if status == 'completed':
            if conclusion == 'success':
                print(f"\n✅ СБОРКА #{run_id} УСПЕШНО ЗАВЕРШЕНА!")
                return True
            else:
                print(f"\n❌ СБОРКА #{run_id} ПРОВАЛИЛАСЬ!")
                get_run_logs(run_id)
                return False
        
        print("⏳ Ожидание завершения сборки... (проверка через 30 сек)")
        time.sleep(30)

if __name__ == "__main__":
    success = monitor_build()
    sys.exit(0 if success else 1)
