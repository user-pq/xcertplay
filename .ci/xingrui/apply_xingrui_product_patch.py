#!/usr/bin/env python3
from __future__ import annotations
import struct,zlib,sys
from pathlib import Path

def rep(p,o,n,l):
    s=p.read_text(encoding='utf-8'); c=s.count(o)
    if c!=1: raise SystemExit(f'[FAIL] {l}: {c} matches in {p}')
    p.write_text(s.replace(o,n,1),encoding='utf-8'); print('[ok]',l)

def png_icon(path: Path):
    w=h=192; bg=(8,20,38,255); cyan=(77,211,255,255); white=(235,245,255,255)
    px=[list(bg)*w for _ in range(h)]
    def dot(x,y,c,r=2):
        for yy in range(max(0,y-r),min(h,y+r+1)):
            for xx in range(max(0,x-r),min(w,x+r+1)):
                if (xx-x)**2+(yy-y)**2<=r*r: px[yy][xx*4:xx*4+4]=c
    def line(x0,y0,x1,y1,c,r=2):
        dx=abs(x1-x0); sx=1 if x0<x1 else -1; dy=-abs(y1-y0); sy=1 if y0<y1 else -1; e=dx+dy
        while True:
            dot(x0,y0,c,r)
            if x0==x1 and y0==y1: break
            e2=2*e
            if e2>=dy: e+=dy; x0+=sx
            if e2<=dx: e+=dx; y0+=sy
    for a,b in [((48,104),(62,72)),((62,72),(78,60)),((78,60),(114,60)),((114,60),(130,72)),((130,72),(144,104)),
                ((46,104),(40,112)),((40,112),(40,136)),((40,136),(152,136)),((152,136),(152,112)),((152,112),(146,104)),
                ((58,106),(72,106)),((120,106),(134,106)),((72,82),(120,82)),((56,136),(56,148)),((136,136),(136,148))]:
        line(*a,*b,white,3)
    line(57,116,73,116,cyan,4); line(119,116,135,116,cyan,4); line(78,126,114,126,white,2)
    raw=b''.join(b'\x00'+bytes(row) for row in px)
    def chunk(t,d): return struct.pack('>I',len(d))+t+d+struct.pack('>I',zlib.crc32(t+d)&0xffffffff)
    out=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(raw,9))+chunk(b'IEND',b'')
    path.parent.mkdir(parents=True,exist_ok=True); path.write_bytes(out); print('[ok] Xingrui OEM icon',len(out),'bytes')

def main():
    r=Path(sys.argv[1] if len(sys.argv)>1 else '.').resolve()
    a=r/'common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt'; h=r/'common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt'
    p=r/'common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt'; m=r/'mobile/src/main/AndroidManifest.xml'
    for f in list((r/'common/src/main/res').glob('values*/strings.xml'))+[r/'mobile/src/main/res/values/strings.xml']:
        if f.exists(): f.write_text(f.read_text(encoding='utf-8').replace('<string name="app_name">Car</string>','<string name="app_name">diplay</string>'),encoding='utf-8')
    rep(m,'android:icon="@drawable/ic_xingrui_car"','android:icon="@drawable/ic_carplay"','restore app icon')
    rep(m,'android:roundIcon="@drawable/ic_xingrui_car"','android:roundIcon="@drawable/ic_carplay"','restore round app icon')
    rep(p,'    const val DEFAULT_MANUFACTURER = "DiPlay"\n    const val DEFAULT_MODEL = "DiPlay"\n    const val DEFAULT_OEM_LABEL = "BYD"\n',
          '    const val DEFAULT_MANUFACTURER = "Geely"\n    const val DEFAULT_MODEL = "Xingrui E02"\n    const val DEFAULT_OEM_LABEL = "Xingrui"\n','Xingrui OEM identity')
    rep(h,'        oemLabel = AirPlayPersistence.loadOemLabel(this)\n','        oemLabel = AirPlayPersistence.loadOemLabel(this).let { if (it.equals("BYD", ignoreCase = true)) "Xingrui" else it }\n','migrate BYD label')
    png_icon(r/'common/src/main/res/raw/xingrui_oem_icon.png')
    s=h.read_text(encoding='utf-8'); c=s.count('R.raw.placeholder_icon')
    if c<1: raise SystemExit('[FAIL] no placeholder OEM icon')
    h.write_text(s.replace('R.raw.placeholder_icon','R.raw.xingrui_oem_icon').replace('text = "完整 Car 设置"','text = "完整设置"'),encoding='utf-8'); print('[ok] CarPlay OEM icon refs',c)
    rep(a,'打开 DiPlay 后自动连接已配对 iPhone。','打开 diplay 后自动连接已配对 iPhone。','app name in wireless-only settings')
    s=a.read_text(encoding='utf-8')
    if '有线 USB CarPlay' in s or '无线 / 有线 CarPlay' in s:
        raise SystemExit('[FAIL] wired CarPlay option leaked into Xingrui UI')
    print('Xingrui product patch applied: wireless-only UI retained.')
if __name__=='__main__': main()
