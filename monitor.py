import os
import sys
import time
import json
import subprocess

def run_command(cmd, shell=True):
    """Вспомогательная функция для безопасного выполнения команд терминала"""
    result = subprocess.run(cmd, shell=shell, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return result

print("📦 1. Автоматически добавляем ВСЕ измененные и новые файлы в индекс Git...")
run_command("git add -A")

print("📝 2. Создаем автоматический коммит с текущими правками...")
commit_res = run_command('git commit -m "fix: universal build with background service and live download progress"')

# Получаем имя текущей ветки
branch_res = run_command("git branch --show-current")
current_branch = branch_res.stdout.strip() or "feature/ktor-server-public-storage"

print(f"🚀 3. Отправляем изменения в ветку {current_branch} на GitHub...")
push_res = run_command(f"git push origin {current_branch}")
if push_res.returncode != 0:
    print("❌ Ошибка при выполнении git push:")
    print(push_res.stderr)
    sys.exit(1)

print("\n⏳ 4. Ожидаем регистрации новой сборки на GitHub Actions (7 секунд)...")
time.sleep(7)

# Получаем ID самой последней сборки для нашей ветки
run_list_res = run_command(f"gh run list --branch {current_branch} --limit 1 --json databaseId")
try:
    runs = json.loads(run_list_res.stdout)
    run_id = runs[0]["databaseId"]
    print(f"🎯 УСПЕХ: Перехвачен ID запущенной сборки: {run_id}")
except Exception as e:
    print("❌ Не удалось получить ID новой сборки. Проверьте установку 'gh' и авторизацию.")
    sys.exit(1)

print("\n🔄 5. Начинаем мониторинг компиляции Gradle в реальном времени...")
print("=============================================================")

while True:
    view_res = run_command(f"gh run view {run_id} --json status,conclusion")
    try:
        status_data = json.loads(view_res.stdout)
        status = status_data.get("status")
        conclusion = status_data.get("conclusion")
    except Exception:
        print("⏳ Ожидание обновления статуса от GitHub API...")
        time.sleep(20)
        continue

    if status == "completed":
        if conclusion == "success":
            print(f"\n✅ УСПЕХ! Сборка №{run_id} полностью и успешно завершена.")
            break
        else:
            print(f"\n❌ Сборка №{run_id} упала. Извлекаем критические логи ошибок:")
            print("=============================================================")
            log_res = run_command(f"gh run view {run_id} --log-failed")
            # Фильтруем важные строки ошибок компиляции Kotlin / Android Gradle Plugin
            for line in log_res.stdout.splitlines():
                if any(x in line for x in ["e: ", "Unresolved reference", "FAILED", "Expecting", "UnsatisfiedLinkError"]):
                    print(line)
            sys.exit(1)
    else:
        print(f"⏳ Статус компиляции: [{status.upper()}]... Проверка через 20 секунд.")
        time.sleep(20)

print("\n📦 6. Получаем ссылку на скомпилированный артефакт...")
artifact_res = run_command(f"gh api repos/:owner/:repo/actions/runs/{run_id}/artifacts")
try:
    artifacts_data = json.loads(artifact_res.stdout)
    artifact_id = artifacts_data["artifacts"][0]["id"]
except Exception:
    print("❌ Ошибка: Не удалось найти скомпилированный APK в артефактах сборки.")
    sys.exit(1)

print("📂 7. Создаем локальные директории и скачиваем ZIP с индикатором прогресса...")
run_command("mkdir -p ./build_artifacts")
token_res = run_command("gh auth token")
token = token_res.stdout.strip()

# Формируем команду curl БЕЗ флага -s и БЕЗ перехвата stdout во внутренний буфер.
# Флаг -# (или стандартный вывод) покажет прогресс-бар прямо в консоли Termux.
download_cmd = f'curl -L -# -H "Authorization: Bearer {token}" "https://api.github.com/repos/LeonidYasin/gallery_server/actions/artifacts/{artifact_id}/zip" -o ./build_artifacts/artifact.zip'

# Вызываем через полноценный системный шелл, привязанный к текущему терминалу
subprocess.run(download_cmd, shell=True)

print("\n🔓 8. Распаковываем архив...")
run_command("unzip -o ./build_artifacts/artifact.zip -d ./build_artifacts/")
run_command("rm ./build_artifacts/artifact.zip")

print("📲 9. Переносим финальный APK в папку Загрузок устройства...")
# Ищем путь к распакованному APK файлу
for root, dirs, files in os.walk("./build_artifacts"):
    for file in files:
        if file.endswith(".apk"):
            apk_source = os.path.join(root, file)
            run_command(f'cp "{apk_source}" /sdcard/Download/app-debug.apk')
            print("=============================================================")
            print("🎉 ИДЕАЛЬНО! Новый фоновый APK успешно обновлен и сохранен в:")
            print("👉 /sdcard/Download/app-debug.apk")
            print("=============================================================")
            sys.exit(0)

print("❌ Ошибка: Внутри скачанного архива не найден APK-файл.")
