from pathlib import Path
import sys, zipfile
folder=Path(sys.argv[1]).resolve()
with zipfile.ZipFile(folder/'resources.apk') as source, zipfile.ZipFile(folder/'unsigned.apk','w',compression=zipfile.ZIP_DEFLATED) as target:
    for item in source.infolist():
        target.writestr(item,source.read(item.filename))
    for dex in (folder/'dex').glob('*.dex'):
        target.write(dex,dex.name)
