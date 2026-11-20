#!/usr/bin/env python3
# Compare isolated baseline/candidate MuxCli classes. Requires ffmpeg and Java.
import pathlib,subprocess,time,statistics,hashlib
r=pathlib.Path(__file__).resolve().parents[2];t=r/'build/timing';t.mkdir(parents=True,exist_ok=True)
def run(cmd):
 p=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
 if p.returncode: print(p.stdout)
 p.check_returncode();return p.stdout
import os
b=pathlib.Path(os.environ['BASELINE_CP'])
candidate=os.environ['CANDIDATE_CP']
run(['ffmpeg','-nostdin','-v','error','-y','-f','lavfi','-i','testsrc2=s=1920x1080:r=30:d=12','-an','-c:v','libvpx-vp9','-deadline','realtime','-cpu-used','8','-row-mt','1','-b:v','2M','-g','60','-pix_fmt','yuv420p','-f','webm','-dash','1',str(t/'video.webm')])
run(['ffmpeg','-nostdin','-v','error','-y','-f','lavfi','-i','sine=f=440:d=12','-ac','2','-ar','48000','-c:a','aac','-b:a','128k','-vn',str(t/'audio.m4a')])
times={'baseline':[],'candidate':[]}
for i in range(6):
 for label in (['baseline','candidate'] if i%2==0 else ['candidate','baseline']):
  out=t/(label+'.mp4');cp=b if label=='baseline' else candidate
  start=time.perf_counter();run(['java','-cp',str(cp),'paradox.dl.MuxCli',str(t/'video.webm'),str(t/'audio.m4a'),str(out)]);elapsed=time.perf_counter()-start
  if i:times[label].append(elapsed)
 assert (t/'baseline.mp4').read_bytes()==(t/'candidate.mp4').read_bytes()
print('REAL WEBM12s: all6 whole-MP4 comparisons identical; output', (t/'candidate.mp4').stat().st_size,hashlib.sha256((t/'candidate.mp4').read_bytes()).hexdigest(),flush=True)
for k,v in times.items():print(k,'elapsed process seconds',v,'median',statistics.median(v),flush=True)
print('Includes JVM startup and cache effects, not device saving latency.',flush=True)
