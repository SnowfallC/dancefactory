import os,sys,time,threading,subprocess,json,statistics,warnings
from pathlib import Path
import numpy as np
root=Path(os.getenv('FORMATION_BENCHMARK_DIR', str(Path(__file__).resolve().parents[2] / 'target/player-comparison')))
sys.path.insert(0,str(root/'python'))
import soundcard as sc
capture=[];running=True;ready=threading.Event()
def record():
    microphone=sc.get_microphone(sc.default_speaker().id,include_loopback=True)
    with microphone.recorder(samplerate=48000,blocksize=480) as recorder:
        ready.set()
        while running:
            data=recorder.record(numframes=480);capture.append((time.time(),data.copy()))
thread=threading.Thread(target=record);thread.start();ready.wait(5)
with (root/'javafx-av-test.log').open('w',encoding='utf-8') as log:
    result=subprocess.run(['cmd','/c','mvnw.cmd','-q','-Dtest=MpvAvIntegrationTest',f'-DavRegression={root}/sync-1080p60.mp4',f'-Dformation.mpv.library={root}/mpv/libmpv-2.dll','test'],stdout=log,stderr=subprocess.STDOUT)
running=False;thread.join(5)
assert result.returncode==0,result.returncode
start,end=map(lambda v:int(v)/1000,(root/'javafx-av-phase.txt').read_text().split(','))
data=np.concatenate([data for stamp,data in capture]);offset=min(stamp-(i+1)*.01 for i,(stamp,data) in enumerate(capture))
mono=np.max(np.abs(data),axis=1);above=mono>.05
edges=np.flatnonzero(above & ~np.r_[False,above[:-1]])
beeps=[]
for sample in edges:
    stamp=offset+sample/48000
    if start<stamp<end and (not beeps or stamp-beeps[-1]>.5):beeps.append(stamp)
flashes=[int(line.split(',')[0])/1000 for line in (root/'javafx-av-flashes.csv').read_text().splitlines()]
deviations=[(stamp-min(beeps,key=lambda beep:abs(beep-stamp)))*1000 for stamp in flashes if start+1<stamp<end-1]
summary={'source':'H264 1920x1080 60fps long GOP 5s + AAC; preview 1280x720 JavaFX ImageView','beeps':len(beeps),'flashes':len(flashes),'offsetMs':deviations,'medianMs':statistics.median(deviations),'p95AbsMs':float(np.percentile(np.abs(deviations),95)),'driftFirstToLastMs':statistics.median(deviations[-5:])-statistics.median(deviations[:5]),'method':'WASAPI output loopback vs JavaFX image update; no physical display/speaker latency measurement'}
(root/'javafx-av-result.json').write_text(json.dumps(summary,indent=2),encoding='utf-8');print(json.dumps(summary,indent=2))
