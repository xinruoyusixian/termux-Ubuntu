import paramiko, time, sys

HOST, PORT, USER, PWD = '192.168.100.168', 2222, 'root', 'root'
R = '/data/data/com.termux/files/ubuntu'
OUT = '/data/local/tmp/new-rootfs.tar.gz'

script = f'''
set -x
# 1) 停桌面，避免打包时文件在变
pkill -u alan -f google-chrome 2>/dev/null
pkill -u alan -x fcitx5 2>/dev/null
pkill -u alan -f xfce4-session 2>/dev/null
pkill -u alan -f xfce4-panel 2>/dev/null
pkill -u alan -f xfdesktop 2>/dev/null
pkill -u alan -f xfwm4 2>/dev/null
pkill -u alan -f Thunar 2>/dev/null
pkill -u alan -f dbus-daemon 2>/dev/null
sleep 2

# 2) 清缓存
rm -rf {R}/var/cache/apt/archives/* {R}/home/alan/.cache/* {R}/root/.cache/* {R}/tmp/* 2>/dev/null
rm -rf {R}/home/alan/.config/google-chrome/Default/Cache {R}/home/alan/.config/google-chrome/Default/Code\\ Cache {R}/home/alan/.config/google-chrome/Default/GPUCache {R}/home/alan/.config/google-chrome/ShaderCache {R}/home/alan/.config/google-chrome/Crash\\ Reports 2>/dev/null

# 3) 卸载挂载点
for m in dev/shm mnt/sdcard dev/pts dev sys proc; do umount {R}/$m 2>/dev/null; done

# 4) 打包
cd {R} || exit 1
rm -f {OUT}
du -sh {R}
tar -cf - --numeric-owner --exclude=./proc --exclude=./sys --exclude=./dev --exclude=./tmp --exclude=./mnt/sdcard . 2>/tmp/tar.err | gzip -1 > {OUT}
echo "RC=$?"
ls -la {OUT}
tail -3 /tmp/tar.err
'''

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, PORT, USER, PWD, timeout=10)
stdin, stdout, stderr = c.exec_command('bash -s', timeout=3600)
stdin.write(script)
stdin.channel.shutdown_write()
t0 = time.time()
out = stdout.read().decode('utf-8', 'replace')
err = stderr.read().decode('utf-8', 'replace')
print(out[-4000:])
print('--- STDERR ---')
print(err[-2000:])
print(f'elapsed {time.time()-t0:.0f}s')
c.close()
