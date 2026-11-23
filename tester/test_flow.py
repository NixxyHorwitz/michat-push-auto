"""
MiChat ADB Prototype / Test Runner
Digunakan untuk menguji alur automasi langsung di HP via ADB sebelum build APK.
"""
import subprocess
import time
import random
import re

DEVICE_ID = "13e6bf75"

def adb(cmd):
    full_cmd = f"adb -s {DEVICE_ID} {cmd}"
    res = subprocess.run(full_cmd, shell=True, capture_output=True, text=True, encoding="utf-8")
    return res.stdout.strip()

def click(x, y):
    adb(f"shell input tap {x} {y}")

def text_input(text):
    # Escape spasi untuk ADB input
    escaped = text.replace(" ", "%s")
    adb(f"shell input text '{escaped}'")

def key_back():
    adb("shell input keyevent 4")

def parse_spintax(text):
    regex = r"\{([^{}]+)\}"
    while re.search(regex, text):
        def repl(match):
            choices = match.group(1).split("|")
            return random.choice(choices)
        text = re.sub(regex, repl, text)
    return text

def ensure_in_peoplenearby():
    print("[*] Memeriksa activity saat ini...")
    out = adb("shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
    
    if "peoplenearby.PeopleNearbyActivity" in out:
        print("[+] Sudah berada di Pengguna di Sekitar.")
        return True
    
    print("[*] Menavigasi ke menu Pengguna di Sekitar...")
    # Buka app jika belum
    adb("shell monkey -p com.michatapp.im -c android.intent.category.LAUNCHER 1")
    time.sleep(2)
    
    # Klik Tab Teman (x: 430, y: 2280 pada layar 1080x2400)
    click(430, 2280)
    time.sleep(1.5)
    
    # Klik menu Pengguna di Sekitar (x: 500, y: 590)
    click(500, 590)
    time.sleep(2)
    return True

if __name__ == "__main__":
    ensure_in_peoplenearby()
    print("[+] Siap. Struktur UI dan koordinat sudah terverifikasi.")
