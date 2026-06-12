import os
import sys
import time
import json
import subprocess
import re

def run_command_live(cmd):
    """Выполняет команду, транслируя её вывод напрямую в консоль Termux в реальном времени"""
    return subprocess.run(cmd, shell=True)

def run_command_capture(cmd):
    """Вспомогательная функция для скрытого сбора данных (например, JSON от API)"""
    result = subprocess.run(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return result

def parse_and_print_gradle_errors(run_id):
    """Интеллектуальный парсер логов сборки GitHub Actions.
    Ищет только корневые причины падения компиляции Android/Kotlin."""
    print("\n🔍 [АНАЛИЗАТОР ОШИБОК] Извлекаем и фильтруем лог краша сборки...")
    print("=============================================================")
    
    # Запрашиваем лог только проваленных шагов сборки
    log_res = run_command_capture(f"gh run view {run_id} --log-failed")
    raw_logs = log_res.stdout
    
    if not raw_logs.strip():
        print("⚠️  Сервер не вернул детальных логов. Возможно, сборка прервана на стороне GitHub.")
        return

    lines = raw_logs.splitlines()
    critical_errors = []
    
    # Регулярные выражения для поиска типичных ошибок компиляции Kotlin/Java и манифеста
    kotlin_err_regex = re.compile(r"(e: .*|\bUnresolved reference\b.*|\bExpecting\b.*)")
    manifest_err_regex = re.compile(r"(.*Manifest merger failed.*|.*Element service#.*)")
    gradle_fail_regex = re.compile(r"(.*Execution failed for task.*|.*FAILED.*)")

    for line in lines:
        clean_line = line.strip()
        # Исключаем неинформативный шум из логов GitHub Actions (таймстампы и служебные теги)
        if "##[error]" in clean_line:
            clean_line = clean_line.replace("##[error]", "❌ ОШИБКА: ")
        
        # Фильтруем строку по паттернам критических ошибок
        if (kotlin_err_regex.search(clean_line) or 
            manifest_err_regex.search(clean_line) or 
            ("error:" in clean_line.lower() and not "0 error" in clean_line.lower()) or
            "exception" in clean_line.lower()):
            
            if clean_line not in critical_errors:
                critical_errors.append(clean_line)
        
        # Захватываем таску, на которой всё упало
        elif gradle_fail_regex.search(clean_line):
            if clean_line not in critical_errors:
                critical_errors.append(f"💥 {clean_line}")

    if critical_errors:
        print(f"🚨 ОБНАРУЖЕНО КРИТИЧЕСКИХ ОШИБОК СБОРКИ: {len(critical_errors)}\n")
        for err in critical_errors:
            # Делаем вывод удобочитаемым: подсвечиваем пути к файлам, если они есть
            if "/src/" in err:
                # Выделяем имя файла для фокуса
                parts = err.split("/src/")
                print(f"📍 .../src/{parts[-1]}")
            else:
                print(f"⚠️  {err}")
    else:
        print("❓ Специфическая ошибка сборки. Выводим последние 20 строк лога:")
        print("-------------------------------------------------------------")
        for line in lines[-20:]:
            print(line)
            
    print("=============================================================")

print("🔍 [ЭТАП 1] Проверка текущего состояния репозитория...")
print("-------------------------------------------------------------")
run_command_live("git status")
print("-------------------------------------------------------------")

print("\n📦 [ЭТАП 2] Добавление ВСЕХ измененных и новых файлов в индекс Git...")
run_command_live("git add -A")

print("\n📝 [ЭТАП 3] Создание коммита с подробным выводом изменений...")
print("-------------------------------------------------------------")
commit_res = run_command_live('git commit -m "fix: foreground service infrastructure and smart error log checking" --verbose')
print("-------------------------------------------------------------")

# Получаем имя текущей ветки
branch_res = run_command_capture("git branch --show-current")
current_branch = branch_res.stdout.strip() or "feature/ktor-server-public-storage"

print(f"\n🚀 [ЭТАП 4] Отправка изменений в ветку '{current_branch}' на GitHub...")
print("-------------------------------------------------------------")
push_res = run_command_live(f"git push origin {current_branch}")
if push_res.returncode != 0:
    print("\n❌ Ошибка при выполнении git push! Процесс остановлен.")
    sys.exit(1)
print("-------------------------------------------------------------")

print("\n⏳ [ЭТАП 5] Ожидание регистрации новой сборки на сервере GitHub Actions...")
for i in range(7, 0, -1):
    print(f"Поиск сборки начнется через {i} сек...", end="\r")
    time.sleep(1)

# Получаем ID самой последней сборки для нашей ветки
run_list_res = run_command_capture(f"gh run list --branch {current_branch} --limit 1 --json databaseId,number,status")
try:
    runs = json.loads(run_list_res.stdout)
    run_id = runs[0]["databaseId"]
    run_number = runs[0].get("number", "N/A")
    print(f"\n🎯 СБОРКА НАЙДЕНА: Перехвачен ID процесса Actions: {run_id} (Номер сборки: #{run_number})")
except Exception as e:
    print("\n❌ Не удалось получить ID новой сборки. Убедитесь, что 'gh auth status' выполнен успешно.")
    print(f"Сырой ответ от gh: {run_list_res.stdout}")
    sys.exit(1)

print("\n🔄 [ЭТАП 6] Мониторинг удаленной компиляции Gradle на серверах GitHub...")
print("=============================================================")

last_status = ""
while True:
    view_res = run_command_capture(f"gh run view {run_id} --json status,conclusion,updatedAt")
    try:
        status_data = json.loads(view_res.stdout)
        status = status_data.get("status")
        conclusion = status_data.get("conclusion")
        updated_at = status_data.get("updatedAt", "")
    except Exception:
        print("⏳ [API] Ожидание ответа от GitHub API (возможна нагрузка на сеть)...")
        time.sleep(15)
        continue

    if status != last_status:
        print(f"⏰ [{updated_at}] Смена статуса сборки: -> {status.upper()}")
        last_status = status

    if status == "completed":
        if conclusion == "success":
            print(f"\n✅ УСПЕХ! Компиляция Gradle для сборки №{run_id} успешно завершена.")
            break
        else:
            print(f"\n❌ КРИТИЧЕСКАЯ ОШИБКА: Сборка №{run_id} провалилась (СТАТУС: {conclusion}).")
            # Вызываем функцию умного поиска ошибок вместо вывода всего лога
            parse_and_print_gradle_errors(run_id)
            sys.exit(1)
    else:
        print(f"   ...процесс сборки продолжается... Текущий статус: [{status.upper()}] (проверка через 15 сек)")
        time.sleep(15)

print("\n📦 [ЭТАП 7] Запрос информации об удаленных артефактах (APK файлах)...")
artifact_res = run_command_capture(f"gh api repos/:owner/:repo/actions/runs/{run_id}/artifacts")
try:
    artifacts_data = json.loads(artifact_res.stdout)
    artifact = artifacts_data["artifacts"][0]
    artifact_id = artifact["id"]
    artifact_name = artifact["name"]
    artifact_size = artifact["size_in_bytes"]
    print(f"📊 Найден артефакт: '{artifact_name}' | Размер: {artifact_size / (1024*1024):.2f} MB")
except Exception:
    print("❌ Ошибка: Сборка завершилась, но скомпилированный APK-артефакт не найден в репозитории.")
    print(f"Ответ API: {artifact_res.stdout}")
    sys.exit(1)

print("\n📂 [ЭТАП 8] Скачивание ZIP-архива сборки с интерактивным прогресс-баром...")
run_command_live("mkdir -p ./build_artifacts")
token_res = run_command_capture("gh auth token")
token = token_res.stdout.strip()

# Используем curl с подробным индикатором прогресса (-#) напрямую в терминал
download_cmd = f'curl -L -# -H "Authorization: Bearer {token}" "https://api.github.com/repos/LeonidYasin/gallery_server/actions/artifacts/{artifact_id}/zip" -o ./build_artifacts/artifact.zip'
run_command_live(download_cmd)

print("\n🔓 [ЭТАП 9] Распаковка архива артефактов...")
print("-------------------------------------------------------------")
run_command_live("unzip -o ./build_artifacts/artifact.zip -d ./build_artifacts/")
run_command_live("rm ./build_artifacts/artifact.zip")
print("-------------------------------------------------------------")

print("\n📲 [ЭТАП 10] Поиск скомпилированного APK и перенос в общую память устройства...")
print("-------------------------------------------------------------")
apk_found = False
for root, dirs, files in os.walk("./build_artifacts"):
    for file in files:
        if file.endswith(".apk"):
            apk_source = os.path.join(root, file)
            file_size = os.path.getsize(apk_source)
            print(f"🎯 Найден целевой файл: {file}")
            print(f"📏 Локальный размер файла после распаковки: {file_size / (1024*1024):.2f} MB")
            
            print("🚚 Копирование в /sdcard/Download...")
            run_command_live(f'cp -v "{apk_source}" /sdcard/Download/app-debug.apk')
            apk_found = True
            break

print("-------------------------------------------------------------")
if apk_found:
    print("=============================================================")
    print("🎉 ИДЕАЛЬНО! Полный цикл логирования завершен.")
    print("👉 Новый фоновый APK готов к установке по пути:")
    print("📍 /sdcard/Download/app-debug.apk")
    print("=============================================================")
    sys.exit(0)
else:
    print("❌ Ошибка: Распаковка прошла успешно, но внутри архива не оказалось файлов с расширением .apk.")
    sys.exit(1)
