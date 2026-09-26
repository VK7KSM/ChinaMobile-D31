p=$(readlink -f "__BYNAME__/__NAME__"); if [ -b "$p" ]; then n=${p##*/}; printf "%s|" "$p"; tr -d "\n" < /sys/class/block/$n/start; printf "|"; cat /sys/class/block/$n/size; fi
