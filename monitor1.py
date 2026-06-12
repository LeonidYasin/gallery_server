import os
import sys
import time
import json
import subprocess

def run_command_live(cmd):
    """Выполняет команду, транслируя её вывод напрямую в консоль Termux в реальном времени"""
    return subprocess.run(cmd, shell=True)

def run_command_capture(cmd):
    """Вспомогательная функция для скрытого сбора данных (например, JSON от API)"""
    result = subprocess.run(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return result

print("🔍 [ЭТАП 1] Проверка текущего состояния репозитория...")
print("-------------------------------------------------------------")
run_command_live("git status")
print("-------------------------------------------------------------")

print("\n📦 [ЭТАП 2] Добавление ВСЕХ измененных и новых файлов в индекс Git...")
run_command_live("git add -A")

print("\n📝 [ЭТАП 3] Создание коммита с подробным выводом изменений...")
print("-------------------------------------------------------------")
# Флаг --verbose заставит git показать, что именно записывается в коммит
commit_res = run_command_live('git commit -m "fix: foreground service infrastructure and verbose logging updates" --verbose')
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
            print("Вытягиваем полный лог ошибок компиляции:")
            print("=============================================================")
            # Выводим логи проваленных шагов сборки без скрытия напрямую в консоль
            run_command_live(f"gh run view {run_id} --log-failed")
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
# Ключ -o перезаписывает файлы без глупых вопросов, выводя структуру папок
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
            
            # Копируем с флагом -v (verbose), чтобы система подтвердила копирование
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
