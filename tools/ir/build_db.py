import os, re, json, subprocess, gzip, collections, sys
ROOT='/tmp/irdb/TVs'
KEYMAP=[
 ('power', r'^(tv_?)?(power|pwr|power_?(on_?)?off|on_?off|standby|power_toggle)$'),
 ('vol_up', r'^(vol(ume)?[_ ]?(up|\+|plus)|v\+|vol\+)$'),
 ('vol_down', r'^(vol(ume)?[_ ]?(dn|down|\-|minus)|v\-|vol\-)$'),
 ('mute', r'^mute$'),
 ('up', r'^(up|cursor_?up|arrow_?up|dpad_?up|nav_?up)$'),
 ('down', r'^(down|dn|cursor_?down|arrow_?down|dpad_?down|nav_?down)$'),
 ('left', r'^(left|cursor_?left|arrow_?left|dpad_?left|nav_?left)$'),
 ('right', r'^(right|cursor_?right|arrow_?right|dpad_?right|nav_?right)$'),
 ('ok', r'^(ok|enter|select|center|confirm)$'),
 ('back', r'^(back|return|ret)$'),
 ('exit', r'^exit$'),
 ('home', r'^(home|smart_?hub|smart|smart_?menu)$'),
 ('menu', r'^(menu|setup)$'),
 ('settings', r'^(settings|setting|options|tools|quick_?settings)$'),
 ('ch_up', r'^(ch(annel)?[_ ]?(next|up|\+|plus)|p\+|prog\+|ch\+|program_?up)$'),
 ('ch_down', r'^(ch(annel)?[_ ]?(prev|dn|down|\-|minus)|p\-|prog\-|ch\-|program_?down)$'),
 ('input', r'^(input|source|src|av|tv_?av|hdmi|input_?select|av_?tv)$'),
 ('info', r'^(info|display|disp)$'),
 ('guide', r'^(guide|epg)$'),
 ('play_pause', r'^(play_?pause|play/pause|pause_?play)$'),
 ('play', r'^play$'),
 ('pause', r'^pause$'),
 ('stop', r'^stop$'),
 ('rew', r'^(rew|rewind|rwd|fast_?back|backward)$'),
 ('ff', r'^(ff|fast_?forward|fwd|forward|ffwd)$'),
 ('netflix', r'^netflix$'),
 ('youtube', r'^youtube$'),
 ('prime', r'^(prime(_?video)?|amazon(_?prime)?)$'),
]
for d in range(10): KEYMAP.append((str(d), r'^(num_?|key_?|digit_?|n)?%d$'%d))
KEYRE=[(k,re.compile(p)) for k,p in KEYMAP]
def keyof(name):
    n=name.strip().lower().replace(' ','_').replace('-','_') if not name.strip().endswith('-') else name.strip().lower()
    n2=name.strip().lower().replace(' ','_')
    for k,r in KEYRE:
        if r.match(n) or r.match(n2): return k
    return None

def parse(path):
    sigs=[]; cur={}
    for line in open(path, errors='ignore'):
        line=line.strip()
        if not line or line.startswith('#'):
            continue
        if ':' not in line: continue
        k,v=line.split(':',1); k=k.strip(); v=v.strip()
        if k=='name':
            if cur: sigs.append(cur)
            cur={'name':v}
        elif cur is not None:
            cur[k]=v
    if cur: sigs.append(cur)
    return [s for s in sigs if 'name' in s]

def le(hexs): 
    b=[int(x,16) for x in hexs.split()]
    return sum(v<<(8*i) for i,v in enumerate(b))

enc=subprocess.Popen(['/tmp/irc/irenc'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True,bufsize=1)
cache={}
def encode(proto,a,c):
    key=(proto,a,c)
    if key in cache: return cache[key]
    enc.stdin.write(f"{proto} {a:x} {c:x}\n"); enc.stdin.flush()
    out=enc.stdout.readline().split()
    if out[0]=='ERR' or 'E' in out: cache[key]=None; return None
    freq=int(out[0]); seq=[]
    for t in out[1:]:
        lvl=t[0]=='+'; d=int(t[1:])
        if seq and seq[-1][0]==lvl: seq[-1][1]+=d
        else: seq.append([lvl,d])
    while seq and not seq[0][0]: seq.pop(0)
    while seq and not seq[-1][0]: seq.pop()
    r=(freq,[d for _,d in seq]); cache[key]=r; return r

def normraw(freq,data):
    t=[abs(int(x)) for x in data.split()]
    # cap long gaps
    t=[min(x,100000) for x in t]
    if len(t)%2==0: t=t[:-1]
    if sum(t)>1900000: return None
    return (int(freq),t)

brands=collections.OrderedDict()
stats=collections.Counter()
for brand in sorted(os.listdir(ROOT), key=str.lower):
    bdir=os.path.join(ROOT,brand)
    if not os.path.isdir(bdir): continue
    remotes=[]; seen=set()
    for dp,_,fs in os.walk(bdir):
        for f in sorted(fs):
            if not f.endswith('.ir'): continue
            keys={}
            for s in parse(os.path.join(dp,f)):
                k=keyof(s['name'])
                if not k or k in keys: continue
                t=s.get('type','').replace('Filetype','').strip()
                p=None
                if t.startswith('parsed') and 'protocol' in s:
                    try: p=encode(s['protocol'].split()[0], le(s['address']), le(s['command']))
                    except Exception as e: p=None
                elif t.startswith('raw') and 'data' in s and 'frequency' in s:
                    p=normraw(s['frequency'], s['data'])
                if p: keys[k]=p
            if len(keys)<3 or 'power' not in keys and len(keys)<6:
                stats['skipped']+=1; continue
            sig=tuple(sorted((k,v[0],tuple(v[1])) for k,v in keys.items()))
            if sig in seen: stats['dup']+=1; continue
            seen.add(sig)
            model=os.path.splitext(f)[0]
            model=re.sub('^'+re.escape(brand)+r'[_ -]*','',model,flags=re.I) or model
            remotes.append((model,keys))
    if remotes:
        remotes.sort(key=lambda r:(-('power' in r[1]), -len(r[1])))
        brands[brand]=remotes
# pattern table
ptab={}; plist=[]
def pid(p):
    key=(p[0],tuple(p[1]))
    if key not in ptab: ptab[key]=len(plist); plist.append([p[0]]+p[1])
    return ptab[key]
out={'v':1,'src':'Flipper-IRDB (CC0)','b':[]}
for b,rs in brands.items():
    out['b'].append({'n':b.replace('_',' '),'r':[{'m':m,'k':{k:pid(v) for k,v in keys.items()}} for m,keys in rs]})
out['p']=plist
js=json.dumps(out,separators=(',',':'))
os.makedirs(sys.argv[1],exist_ok=True)
with gzip.open(os.path.join(sys.argv[1],'ir_tv.json.gz'),'wt') as f: f.write(js)
print('brands',len(brands),'remotes',sum(len(r) for r in brands.values()),'patterns',len(plist),'json',len(js),'stats',dict(stats))
print({b:len(r) for b,r in brands.items() if b in ['Samsung','LG','Sony','Xiaomi','TCL','Panasonic','Philips','Haier','Hisense','Android','Toshiba','Sharp']})
kc=collections.Counter(k for rs in brands.values() for m,keys in rs for k in keys)
print(kc.most_common())
