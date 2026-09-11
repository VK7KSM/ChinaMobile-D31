#!/system/bin/sh
# 与两个入口一起暂存。部署完成不等于系统身份或核心加载验收。
set -eu
umask 077
locked=0
if [ "${1:-}" = --locked ]; then locked=1; shift; fi
[ "$#" = 5 ] || { echo '需要动作、暂存目录和三个摘要' >&2; exit 2; }
action=$1 stage=$2 expected=$3 anchor=$4 checker=$5
case "$action" in install|replace|finalize|rollback) ;; *) exit 2;; esac
case "$stage" in /data/local/d31-remote/deploy-*) ;; *) exit 2;; esac
case "$stage" in *..*|*//*|*[!a-zA-Z0-9/_-]*) exit 2;; esac
for value in "$expected" "$anchor" "$checker"; do
  [ "${#value}" = 64 ] || exit 2
  case "$value" in *[!0-9a-f]*) exit 2;; esac
done
[ "$(id -u)" = 0 ]
[ "$(getprop ro.build.version.sdk)" = 23 ]
[ "$(getprop ro.product.device)" = hct6735_66_m0 ]
[ "$(getprop ro.product.model)" = hct6737t_66_m0 ]
target=/system/priv-app/D31ElfRemote/D31ElfRemote.apk
hook=/system/bin/install-recovery.sh
start=/system/bin/d31-elfremote-start
marker=/system/etc/d31-elfremote.system
backup="$stage/backup"
hash_check() { echo "$1  $2" | busybox sha256sum -c -; }
for file in "$stage" "$stage/remote.apk" "$stage/migration-check.jar"; do [ ! -L "$file" ]; done
hash_check "$expected" "$stage/remote.apk"
hash_check "$checker" "$stage/migration-check.jar"
export CLASSPATH="$stage/migration-check.jar:$stage/remote.apk"
check() { /system/bin/busybox timeout 200 /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteSystemMigrationCheck "$@"; }
# 写入阶段借用主任务的维护锁；等待监督接替时必须释放。
if [ "$locked" = 0 ] && [ "$action" != finalize ]; then
  exec /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteSystemMigrationCheck locked-run "$0" "$action" "$stage" "$expected" "$anchor" "$checker"
fi
readonly_system() {
  mount -o remount,ro /system || return 1
  busybox awk '$2=="/system" { n++; if ($4 ~ /(^|,)ro(,|$)/) ok++ } END {exit !(n==1 && ok==1)}' /proc/mounts
}
write_result() { printf '%s\n' "$1" > "$stage/status"; sync; echo "$1"; }
restore_files() {
  check guard-files "$stage" || return 1
  mount -o remount,rw /system || return 1
  if [ "$(cat "$backup/route")" = install ]; then
    cp -p "$backup/install-recovery.sh" "$hook" || return 1
    check restore-file-metadata "$stage" || return 1
    rm -f "$marker" "$start" "$target" "$hook.elfremote-new" || return 1
    if [ -d /system/priv-app/D31ElfRemote ]; then rmdir /system/priv-app/D31ElfRemote || return 1; fi
  else
    cp -p "$backup/system.apk" "$target" || return 1
    chcon u:object_r:system_file:s0 "$target" || return 1
    rm -f "$target.new" || return 1
  fi
  sync
  readonly_system
}
rollback() {
  check guard-rollback "$stage" || return 1
  check quiesce "$stage" || return 1
  restore_files || return 1
  check restore-install "$stage" || return 1
  check restore-state "$stage" || return 1
  check restore-controls "$stage" || return 1
  check verify-restored "$stage" || return 1
  write_result RESTORED_APK_AND_SETTINGS_RUNTIME_NOT_VERIFIED
}
failed() {
  rc=$?
  trap - EXIT HUP INT TERM
  set +e
  if [ -f "$backup/ready" ]; then
    if ! rollback > "$stage/rollback.log" 2>&1; then
      readonly_system >> "$stage/rollback.log" 2>&1
      write_result ATTENTION_ROLLBACK_INCOMPLETE
    fi
  fi
  [ "$rc" != 0 ] || rc=1
  exit "$rc"
}
if [ "$action" = rollback ]; then
  [ -f "$backup/ready" ]
  if rollback; then exit 0; else readonly_system || true; write_result ATTENTION_ROLLBACK_INCOMPLETE; exit 1; fi
fi
if [ "$action" = finalize ]; then
  [ -f "$backup/ready" ]
  if ! check verify-system "$stage" > "$stage/system-verification.json" 2> "$stage/system-verification-error.txt"; then
    write_result SYSTEM_IDENTITY_NOT_VERIFIED
    exit 1
  fi
  check restore-state "$stage"
  check restore-controls "$stage"
  check enable-supervision "$stage"
  /system/bin/busybox setsid /system/bin/sh "$start" </dev/null >"$stage/supervisor-start.log" 2>&1 &
  if ! check wait-core "$stage" > "$stage/core-verification.json" 2> "$stage/core-verification-error.txt"; then
    write_result ATTENTION_CORE_NOT_VERIFIED
    exit 1
  fi
  write_result INSTALLED_SYSTEM_IDENTITY_AND_CORE_MAPPING_VERIFIED
  exit 0
fi
[ ! -e "$backup" ] && [ ! -L "$backup" ] || { echo '已有备份，禁止重放' >&2; exit 2; }
for file in "$target.new" "$hook.elfremote-new"; do
  [ ! -e "$file" ] && [ ! -L "$file" ] || { echo '已有临时文件，禁止覆盖' >&2; exit 2; }
done
if [ "$action" = install ]; then
  for file in /system/priv-app/D31ElfRemote "$marker" "$start"; do
    [ ! -e "$file" ] && [ ! -L "$file" ] || { echo '系统托管目标已经存在' >&2; exit 2; }
  done
  hash_check "$anchor" "$hook"
  sh -n "$stage/start.sh"
  sh -n "$stage/install-recovery.sh"
else
  [ -f "$marker" ] && [ ! -L "$marker" ] || exit 2
  hash_check "$anchor" "$target"
fi
check prepare "$action" "$stage" "$expected" "$anchor"
trap failed EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
check quiesce "$stage"
mount -o remount,rw /system
if [ "$action" = install ]; then
  mkdir /system/priv-app/D31ElfRemote
  chmod 0755 /system/priv-app/D31ElfRemote
  cp "$stage/remote.apk" "$target"
  cp "$stage/start.sh" "$start"
  cp "$stage/marker" "$marker"
  chown 0:0 /system/priv-app/D31ElfRemote "$target" "$start" "$marker"
  chmod 0644 "$target" "$marker"
  chmod 0755 "$start"
  chcon u:object_r:system_file:s0 /system/priv-app/D31ElfRemote "$target" "$start" "$marker"
  cp -p "$hook" "$hook.elfremote-new"
  cat "$stage/install-recovery.sh" > "$hook.elfremote-new"
  mv "$hook.elfremote-new" "$hook"
  check restore-file-metadata "$stage"
else
  cp "$stage/remote.apk" "$target.new"
  chown 0:0 "$target.new"
  chmod 0644 "$target.new"
  chcon u:object_r:system_file:s0 "$target.new"
  hash_check "$expected" "$target.new"
  mv "$target.new" "$target"
fi
sync
readonly_system
hash_check "$expected" "$target"
check install "$stage" > "$stage/pm-install.log" 2>&1
check verify-installed "$stage" > "$stage/installed-verification.json"
check restore-state "$stage"
trap - EXIT HUP INT TERM
write_result SYSTEM_COMPONENT_STAGED_FINALIZE_REQUIRED
