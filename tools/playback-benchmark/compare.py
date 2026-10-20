import ctypes as c
import os, sys, time, threading, json, statistics, subprocess
from pathlib import Path
import numpy as np

root = Path(os.getenv('FORMATION_BENCHMARK_DIR', str(Path(__file__).resolve().parents[2] / 'target/player-comparison')))
sys.path.insert(0, str(root / 'python'))
width, height = 640, 360
vp = c.c_void_p

def bind(dll, name, result, *args):
    fn = getattr(dll, name); fn.restype = result; fn.argtypes = args
    return fn

class Frames:
    def __init__(self):
        self.frame = -1; self.last = 0; self.flashes = []; self.log = []; self.white = False
    def display(self, data):
        now = time.perf_counter()
        pix = np.frombuffer(data, dtype=np.uint8).reshape(height, width, 4)
        self.frame = sum((int(pix[330, bit * 32 + 16, 0]) > 128) << bit for bit in range(16))
        self.last = now; self.log.append((now, self.frame))
        white = pix[20, 20, 0] > 128
        if white and not self.white: self.flashes.append((now, self.frame))
        self.white = white

class Param(c.Structure):
    _fields_ = [('type', c.c_int), ('data', vp)]

class Mpv(Frames):
    def __init__(self):
        super().__init__()
        self.dll = c.CDLL(str(root / 'mpv/libmpv-2.dll'))
        d = self.dll
        self.create = bind(d,'mpv_create',vp)
        self.option = bind(d,'mpv_set_option_string',c.c_int,vp,c.c_char_p,c.c_char_p)
        self.init = bind(d,'mpv_initialize',c.c_int,vp)
        self.command = bind(d,'mpv_command',c.c_int,vp,c.POINTER(c.c_char_p))
        self.prop = bind(d,'mpv_get_property',c.c_int,vp,c.c_char_p,c.c_int,vp)
        self.setprop = bind(d,'mpv_set_property_string',c.c_int,vp,c.c_char_p,c.c_char_p)
        self.renderCreate = bind(d,'mpv_render_context_create',c.c_int,c.POINTER(vp),vp,c.POINTER(Param))
        self.update = bind(d,'mpv_render_context_update',c.c_uint64,vp)
        self.render = bind(d,'mpv_render_context_render',c.c_int,vp,c.POINTER(Param))
        self.free = bind(d,'mpv_render_context_free',None,vp)
        self.destroy = bind(d,'mpv_terminate_destroy',None,vp)
        self.ctx = self.create()
        for name,value in [('vo','libmpv'),('hwdec','no'),('pause','yes'),('keep-open','yes'),('config','no'),('terminal','no'),('audio-client-name','Formation benchmark')]:
            assert self.option(self.ctx,name.encode(),value.encode()) >= 0, name
        assert self.init(self.ctx) >= 0
        self.running = True; self.started = threading.Event()
        self.thread = threading.Thread(target=self.renderLoop); self.thread.start(); self.started.wait(10)
    def renderLoop(self):
        self.rc = vp(); api = c.create_string_buffer(b'sw')
        assert self.renderCreate(c.byref(self.rc),self.ctx,(Param*2)(Param(1,c.cast(api,vp)),Param())) >= 0
        size = (c.c_int*2)(width,height); fmt = c.create_string_buffer(b'bgr0'); stride=c.c_size_t(width*4)
        raw=c.create_string_buffer(width*height*4+64); address=(c.addressof(raw)+63)&~63
        params=(Param*5)(Param(17,c.cast(size,vp)),Param(18,c.cast(fmt,vp)),Param(19,c.cast(c.byref(stride),vp)),Param(20,address),Param())
        self.started.set()
        while self.running:
            if self.update(self.rc)&1:
                assert self.render(self.rc,params)>=0
                self.display(c.string_at(address,width*height*4))
            else: time.sleep(.002)
        self.free(self.rc)
    def cmd(self,*args):
        result=self.command(self.ctx,(c.c_char_p*(len(args)+1))(*[str(a).encode() for a in args],None))
        assert result>=0,(args,result)
    def load(self,path): self.cmd('loadfile',str(path))
    def seek(self,seconds): self.cmd('seek',seconds,'absolute+exact')
    def pause(self,value): self.setprop(self.ctx,b'pause',b'yes' if value else b'no')
    def position(self):
        value=c.c_double();self.prop(self.ctx,b'time-pos',5,c.byref(value));return value.value
    def close(self):
        self.cmd('stop');self.running=False;self.thread.join(10);self.destroy(self.ctx)

class Vlc(Frames):
    def __init__(self):
        super().__init__(); base=root/'vlc/vlc-3.0.21';self.dir=os.add_dll_directory(str(base))
        os.environ['VLC_PLUGIN_PATH']=str(base/'plugins');self.dll=c.CDLL(str(base/'libvlc.dll'));d=self.dll
        self.new=bind(d,'libvlc_new',vp,c.c_int,c.POINTER(c.c_char_p))
        self.playerNew=bind(d,'libvlc_media_player_new',vp,vp)
        self.mediaNew=bind(d,'libvlc_media_new_path',vp,vp,c.c_char_p)
        self.setMedia=bind(d,'libvlc_media_player_set_media',None,vp,vp)
        self.releaseMedia=bind(d,'libvlc_media_release',None,vp)
        self.play=bind(d,'libvlc_media_player_play',c.c_int,vp)
        self.setPause=bind(d,'libvlc_media_player_set_pause',None,vp,c.c_int)
        self.setTime=bind(d,'libvlc_media_player_set_time',None,vp,c.c_int64)
        self.nextFrame=bind(d,'libvlc_media_player_next_frame',None,vp)
        self.getTime=bind(d,'libvlc_media_player_get_time',c.c_int64,vp)
        self.stop=bind(d,'libvlc_media_player_stop',None,vp)
        self.releasePlayer=bind(d,'libvlc_media_player_release',None,vp)
        self.release=bind(d,'libvlc_release',None,vp)
        args=[b'--no-video-title-show',b'--no-osd',b'--avcodec-hw=none',b'--file-caching=100',b'--quiet']
        self.ctx=self.new(len(args),(c.c_char_p*len(args))(*args));assert self.ctx
        self.player=self.playerNew(self.ctx);self.buffer=c.create_string_buffer(width*height*4)
        lockType=c.CFUNCTYPE(vp,vp,c.POINTER(vp));unlockType=c.CFUNCTYPE(None,vp,vp,c.POINTER(vp));displayType=c.CFUNCTYPE(None,vp,vp)
        self.lock=lockType(lambda opaque,planes:self.lockFrame(planes));self.unlock=unlockType(lambda *a:None)
        self.displayCb=displayType(lambda *a:self.display(self.buffer.raw))
        bind(d,'libvlc_video_set_callbacks',None,vp,lockType,unlockType,displayType,vp)(self.player,self.lock,self.unlock,self.displayCb,None)
        bind(d,'libvlc_video_set_format',None,vp,c.c_char_p,c.c_uint,c.c_uint,c.c_uint)(self.player,b'RV32',width,height,width*4)
    def lockFrame(self,planes): planes[0]=c.addressof(self.buffer);return None
    def load(self,path):
        media=self.mediaNew(self.ctx,str(path).encode());self.setMedia(self.player,media);self.releaseMedia(media)
        self.play(self.player)
    def seek(self,seconds): self.setTime(self.player,int(seconds*1000))
    def pause(self,value): self.setPause(self.player,int(value))
    def position(self): return self.getTime(self.player)/1000
    def close(self):self.stop(self.player);self.releasePlayer(self.player);self.release(self.ctx)

def waitFor(predicate,timeout=8):
    deadline=time.perf_counter()+timeout
    while time.perf_counter()<deadline:
        if predicate():return True
        time.sleep(.002)
    return False

def generate():
    out=root/'sync-60s.mp4'
    if out.exists():return out
    command=['ffmpeg','-hide_banner','-loglevel','error','-y','-f','rawvideo','-pix_fmt','rgb24','-s','640x360','-r','30','-i','pipe:0','-f','lavfi','-i',r'aevalsrc=if(lt(mod(t\,1)\,0.08)\,0.4*sin(2*PI*1000*t)\,0):s=48000:d=60','-t','60','-c:v','libx264','-preset','fast','-crf','18','-g','150','-pix_fmt','yuv420p','-c:a','aac',str(out)]
    p=subprocess.Popen(command,stdin=subprocess.PIPE)
    for frame in range(1800):
        data=np.zeros((height,width,3),np.uint8);data[60:300,:,1]=50
        data[:60]=255 if frame%30<3 else 0
        data[80:280,(frame*7)%600:(frame*7)%600+40,0]=220
        for bit in range(16):data[300:,bit*32:(bit+1)*32]=255 if (frame>>bit)&1 else 0
        p.stdin.write(data.tobytes())
    p.stdin.close();assert p.wait()==0
    return out

def benchmark(kind):
    import soundcard as sc
    path=Path(os.environ['BENCHMARK_SOURCE']).resolve() if os.getenv('BENCHMARK_SOURCE') else generate(); p=(Mpv if kind=='mpv' else Vlc)(); result={'backend':kind,'source':str(path),'previewSize':[width,height],'seek':[]}
    start=time.perf_counter();p.load(path);assert waitFor(lambda:p.frame>=0),'no initial frame';p.pause(True)
    result['loadMs']=(time.perf_counter()-start)*1000
    time.sleep(.3)
    for target in [17.3,2.1,43.7,8.4,55.2,12.8,31.1,4.7,48.3,22.5]:
        start=time.perf_counter();p.seek(target)
        if kind=='vlc' and os.getenv('VLC_STEP'):
            waitFor(lambda:p.last>=start,.25)
            ok=abs(p.frame/30-target)<=.02
            for step in range(16):
                if ok or p.frame/30>target+.02:break
                previous=p.frame
                p.nextFrame(p.player)
                waitFor(lambda:p.frame!=previous,.25)
                ok=abs(p.frame/30-target)<=.02
        else:ok=waitFor(lambda:abs(p.frame/30-target)<=.02 and p.last>=start,4)
        item={'target':target,'latencyMs':round((time.perf_counter()-start)*1000,2),'frame':p.frame,'errorMs':round((p.frame/30-target)*1000,2),'ok':ok};result['seek'].append(item);print(kind,item,flush=True)
    # 快速交替拖动，最后一次必须落在最终目标附近。
    for target in np.linspace(5,50,30):p.seek(float(target));time.sleep(.02)
    start=time.perf_counter();p.seek(11.7);result['burstFinalOk']=waitFor(lambda:abs(p.frame/30-11.7)<.02 and p.last>=start,4);result['burstFinalMs']=(time.perf_counter()-start)*1000
    p.seek(0);time.sleep(.5);p.flashes.clear();p.log.clear();p.white=False
    capture=[];captureReady=threading.Event();running=True
    def record():
        microphone=sc.get_microphone(sc.default_speaker().id,include_loopback=True)
        with microphone.recorder(samplerate=48000,blocksize=480) as recorder:
            # 建立音频时钟：记录每块返回时间，以最小偏移消除线程调度造成的延迟。
            captureReady.set()
            while running:
                data=recorder.record(numframes=480)
                capture.append((time.perf_counter(),data.copy()))
    thread=threading.Thread(target=record);thread.start();captureReady.wait(5);time.sleep(.3)
    p.pause(False);start=time.perf_counter();time.sleep(float(os.getenv('PLAY_SECONDS','35')));p.pause(True);end=time.perf_counter()
    running=False;thread.join(5)
    data=np.concatenate([d for t,d in capture]);offset=min(t-(i+1)*.01 for i,(t,d) in enumerate(capture))
    mono=np.max(np.abs(data),axis=1);above=mono>.05
    edges=np.flatnonzero(above & ~np.r_[False,above[:-1]])
    beeps=[]
    for sample in edges:
        stamp=offset+sample/48000
        if start-.1<stamp<end and (not beeps or stamp-beeps[-1]>.5):beeps.append(stamp)
    flashes=[t for t,f in p.flashes if start<t<end and f%30<3]
    deviations=[(t-min(beeps,key=lambda a:abs(a-t)))*1000 for t in flashes] if beeps else []
    result.update({'loopbackBeeps':len(beeps),'videoFlashes':len(flashes),'avOffsetMs':deviations,'framesDuringPlayback':len(p.log),'positionAfterPlayback':p.position(),'clockMethod':'WASAPI loopback sample clock aligned by minimum block-return offset; native video callback timestamp; excludes physical display/speaker latency'})
    p.close()
    if deviations: result['avSummary']={'medianMs':statistics.median(deviations),'p95AbsMs':float(np.percentile(np.abs(deviations),95)),'driftFirstToLastMs':statistics.median(deviations[-5:])-statistics.median(deviations[:5])}
    suffix=os.getenv('RUN_SUFFIX','')
    (root/(kind+suffix+'-result.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False,indent=2),flush=True)

if __name__=='__main__':benchmark(sys.argv[1])
