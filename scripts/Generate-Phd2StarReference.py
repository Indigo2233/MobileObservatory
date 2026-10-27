"""Compile original PHD2 QuickLRecon/Star::Find bodies; no application code is imported.

Usage: python scripts/Generate-Phd2StarReference.py D:/Unity/phd2
Reads the pinned Git objects, preserving any local upstream working-tree changes.
"""
import gzip
import hashlib
import json
import math
import pathlib
import random
import subprocess
import sys
import tempfile

root = pathlib.Path(__file__).resolve().parents[1]
upstream = pathlib.Path(sys.argv[1])
commit = "70e9fd178b3122b6553646c60f462cb09335dcc5"
sources = {name: subprocess.check_output(["git", "-C", str(upstream), "show", f"{commit}:src/{name}"]).decode()
           for name in ("image_math.cpp", "star.cpp")}

def function(source, signature):
    start = source.index(signature)
    end = source.index("{", start) + 1
    depth = 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]

shim = r'''
#include <algorithm>
#include <cmath>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>
struct wxString : std::string { using std::string::string; template<class... A> static wxString Format(const char*, A...) { return {}; } };
#define _(x) x
#define ERROR_INFO(x) wxString(x)
#define POSSIBLY_UNUSED(x) (void)(x)
#define wxMin(a,b) std::min(a,b)
#define wxMax(a,b) std::max(a,b)
struct Logger { void Write(const wxString&) {} } Debug;
struct Frame { void Alert(const char*) {} } frame;
Frame* pFrame=&frame;
struct wxPoint { int x,y; wxPoint(int x_=0,int y_=0):x(x_),y(y_){} };
struct Dimensions { int w,h; Dimensions(int w_=0,int h_=0):w(w_),h(h_){} int GetWidth() const{return w;} int GetHeight() const{return h;} };
struct Rect {
 int x=0,y=0,width=0,height=0;
 bool IsEmpty() const{return width==0 || height==0;}
 int GetX()const{return x;} int GetY()const{return y;} int GetWidth()const{return width;} int GetHeight()const{return height;}
 int GetLeft()const{return x;} int GetRight()const{return x+width-1;} int GetTop()const{return y;} int GetBottom()const{return y+height-1;}
};
struct usImage {
 Dimensions Size; Rect Subframe; unsigned BitsPerPixel=16,FrameNum=1; unsigned short Pedestal=0;
 std::vector<unsigned short> storage; unsigned short* ImageData=nullptr;
 bool Init(Dimensions size){Size=size;storage.resize(size.w*size.h);ImageData=storage.data();return false;}
 void Clear(){std::fill(storage.begin(),storage.end(),0);}
 void SwapImageData(usImage& other){storage.swap(other.storage);ImageData=storage.data();other.ImageData=other.storage.data();}
};
struct Star {
 enum FindMode {FIND_CENTROID,FIND_PEAK};
 enum FindResult {STAR_OK,STAR_SATURATED,STAR_LOWSNR,STAR_LOWMASS,STAR_LOWHFD,STAR_HIHFD,STAR_TOO_NEAR_EDGE,STAR_MASSCHANGE,STAR_ERROR};
 enum StarFindLogType {FIND_LOGGING_MINIMAL,FIND_LOGGING_VERBOSE};
 double X=0,Y=0,Mass=0,SNR=0,HFD=0; unsigned short PeakVal=0; FindResult m_lastFindResult=STAR_ERROR;
 void SetXY(double x,double y){X=x;Y=y;} bool IsValid()const{return std::isfinite(X)&&std::isfinite(Y);}
 static bool WasFound(FindResult r){return r==STAR_OK || r==STAR_SATURATED;}
 bool Find(const usImage*,int,int,int,FindMode,double,double,unsigned short,StarFindLogType);
};
'''
shim += function(sources["image_math.cpp"], "bool QuickLRecon(usImage& img)") + "\n"
star = sources["star.cpp"]
shim += star[star.index("struct R2M"):star.index("bool Star::Find(")] + "\n"
shim += function(star, "bool Star::Find(const usImage *pImg, int searchRegion, int base_x, int base_y,") + "\n"
shim += r'''
std::string hex(const usImage& image){std::ostringstream out;out<<std::hex<<std::setfill('0');for(auto p:image.storage)out<<std::setw(4)<<p;return out.str();}
int main(){
 std::cout<<std::setprecision(17);
 std::string line;
 while(std::getline(std::cin,line)){
  std::stringstream row(line);std::vector<std::string> fields;std::string value;
  while(std::getline(row,value,','))fields.push_back(value);
  usImage image;image.Init({std::stoi(fields[1]),std::stoi(fields[2])});image.BitsPerPixel=std::stoi(fields[7]);
  for(size_t i=0;i<image.storage.size();++i) image.storage[i]=std::stoul(fields[9].substr(i*4,4),nullptr,16);
  if(std::stoi(fields[3]))QuickLRecon(image);
  Star s;if(image.Size.w>=25 && image.Size.h>=25)s.Find(&image,std::stoi(fields[6]),std::stoi(fields[4]),std::stoi(fields[5]),Star::FIND_CENTROID,1.5,20,std::stoi(fields[8]),Star::FIND_LOGGING_MINIMAL);
  std::cout<<line<<","<<hex(image)<<","<<s.X<<","<<s.Y<<","<<s.Mass<<","<<s.SNR<<","<<s.HFD<<","<<s.PeakVal<<","<<s.m_lastFindResult<<"\n";
 }
}
'''

rows = []
def add(name, w, h, pixels, recon, bits=16, maximum=None, x=None, y=None):
    rows.append([name, w, h, int(recon), w//2 if x is None else x, h//2 if y is None else y,
                 5, bits, (1<<bits)-1 if maximum is None else maximum, "".join(f"{p:04x}" for p in pixels)])

rng = random.Random(585)
for w, h in ((1,1),(1,9),(9,1),(2,2),(8,6)):
    add(f"edge-{w}-{h}",w,h,[rng.randrange(65536) for _ in range(w*h)],True)
for sigma in (0.8,1.2,1.8,2.5):
    for colored in (False,True):
        for step in range(8):
            x,y=30.1+step*.23,31.17+step*.19
            response=(4,.4,.4,.08) if colored else (1,1,1,1)
            pixels=[round((200,320,320,100)[(py%2)*2+px%2]+1800*response[(py%2)*2+px%2]*
                          math.exp(-((px-x)**2+(py-y)**2)/(2*sigma*sigma))) for py in range(64) for px in range(64)]
            add(f"color-{colored}-{sigma}-{step}",64,64,pixels,True,14,x=round(x),y=round(y))
for bits in (8,16):
    maximum=(1<<bits)-1
    for level in (0.02,0.2,0.9,1.2):
        pixels=[min(maximum,max(0,round(maximum*.01+rng.gauss(0,maximum*.001)+maximum*level*
                math.exp(-((x-31.23)**2+(y-32.71)**2)/8)))) for y in range(64) for x in range(64)]
        add(f"mono-{bits}-{level}",64,64,pixels,False,bits)
add("empty",64,64,[100]*4096,False)
add("hot-pixel",64,64,[50000 if i==32*64+32 else 100 for i in range(4096)],True)
add("annulus-outliers",64,64,[round(200+4000*math.exp(-((x-31.2)**2+(y-32.3)**2)/8)) +
     (2000 if (x,y) in ((40,32),(23,32),(32,42)) else 0) for y in range(64) for x in range(64)],False)
for bits in (8,16):
    add(f"flat-top-{bits}",64,64,[int(((1<<bits)-1)*.7) if 29<=x<=34 and 29<=y<=34 else 10
        for y in range(64) for x in range(64)],True,bits,maximum=0)

header = "name,width,height,recon,baseX,baseY,search,bits,maxADU,inputHex,luminanceHex,x,y,mass,snr,hfd,peak,result\n"
with tempfile.TemporaryDirectory(prefix="phd2-star-reference-") as directory:
    folder=pathlib.Path(directory);source=folder/"reference.cpp";source.write_text(shim,encoding="utf-8")
    exe=folder/("reference.exe" if sys.platform=="win32" else "reference")
    subprocess.run(["g++","-std=c++11","-O2",str(source),"-o",str(exe)],check=True)
    data="\n".join(",".join(map(str,row)) for row in rows)+"\n"
    output=subprocess.check_output([str(exe)],input=data,text=True)
resource=root/"app/src/test/resources/guide"
with gzip.GzipFile(filename=str(resource/"phd2-star-reference.csv.gz"),mode="wb",mtime=0) as compressed:
    compressed.write((header+output).encode())
metadata={"commit":commit,"method":"Unmodified upstream QuickLRecon, hfr and Star::Find bodies; only wx/image containers and logging are shimmed.",
          "samples":len(rows),"sha256":{name:hashlib.sha256(data.encode()).hexdigest() for name,data in sources.items()}}
(resource/"phd2-star-reference.json").write_text(json.dumps(metadata,indent=2)+"\n",encoding="utf-8")
print(json.dumps(metadata,indent=2))
