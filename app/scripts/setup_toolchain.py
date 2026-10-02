"""准备本地工具链：便携版 Maven（无需安装，放在 .tools 下）。

用法：
    .venv\\Scripts\\python.exe scripts/setup_toolchain.py
"""
import io
import os
import zipfile
from pathlib import Path

import requests

from _common import TOOLS_DIR

MAVEN_VERSION = "3.9.9"
MAVEN_URL = (f"https://repo.maven.apache.org/maven2/org/apache/maven/"
             f"apache-maven/{MAVEN_VERSION}/apache-maven-{MAVEN_VERSION}-bin.zip")
# 国内镜像备选
MAVEN_URL_CN = (f"https://maven.aliyun.com/repository/public/org/apache/maven/"
                f"apache-maven/{MAVEN_VERSION}/apache-maven-{MAVEN_VERSION}-bin.zip")


def main():
    TOOLS_DIR.mkdir(parents=True, exist_ok=True)
    target = TOOLS_DIR / f"apache-maven-{MAVEN_VERSION}"
    if (target / "bin" / "mvn.cmd").exists():
        print(f"Maven 已就绪：{target}")
        return
    zip_path = TOOLS_DIR / "maven.zip"
    if not zip_path.exists():
        print("下载 Maven ...")
        ok = False
        for url in (MAVEN_URL_CN, MAVEN_URL):
            try:
                r = requests.get(url, timeout=180, stream=True)
                if r.status_code == 200:
                    with open(zip_path, "wb") as f:
                        for chunk in r.iter_content(1 << 20):
                            f.write(chunk)
                    ok = True
                    print(f"下载完成：{url}")
                    break
            except Exception as e:
                print("下载失败：", e)
        if not ok:
            raise SystemExit("Maven 下载失败，请手动下载后解压到 .tools/apache-maven-x.x.x")
    print("解压中 ...")
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(TOOLS_DIR)
    print(f"Maven 已就绪：{target}")


if __name__ == "__main__":
    main()
