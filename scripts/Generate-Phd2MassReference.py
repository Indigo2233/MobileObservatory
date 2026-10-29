"""Compile the unmodified pinned PHD2 MassChecker with an injected clock.

Usage: python scripts/Generate-Phd2MassReference.py D:/Unity/phd2
Reads Git objects and preserves all upstream working-tree files.
"""
import hashlib
import json
from pathlib import Path
import random
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
commit = '70e9fd178b3122b6553646c60f462cb09335dcc5'
source = subprocess.check_output(['git', '-C', sys.argv[1], 'show', commit + ':src/guider_multistar.cpp'])
text = source.decode().replace('\r\n', '\n')
body = text[text.index('class MassChecker\n'):text.index('static const double DefaultMassChangeThreshold')]
shim = r'''
#include <algorithm>
#include <deque>
#include <iomanip>
#include <iostream>
#include <limits>
#include <memory>
#include <sstream>
#include <string>
using wxLongLong_t = long long;
static long long nowMs;
struct Clock {long long GetValue() const {return nowMs;}};
Clock wxGetUTCTimeMillis(){return {};}
'''
main = r'''
int main(){
 std::cout<<std::setprecision(17);
 std::string line,last; std::unique_ptr<MassChecker> checker;
 while(std::getline(std::cin,line)){
  std::stringstream row(line);std::string name;int index,exposure,automatic;double mass,threshold;char comma;
  std::getline(row,name,',');row>>index>>comma>>nowMs>>comma>>exposure>>comma>>automatic>>comma>>mass>>comma>>threshold;
  if(name!=last){checker.reset(new MassChecker());last=name;}
  double limits[4];std::fill(limits,limits+4,std::numeric_limits<double>::quiet_NaN());bool rejected=false;
  if(mass>=0){checker->SetExposure(exposure,automatic);rejected=checker->CheckMass(mass,threshold,limits);checker->AppendData(mass);}
  std::cout<<line<<","<<rejected;
  for(auto limit:limits)std::cout<<","<<limit;
  std::cout<<"\n";
 }
}
'''
rows = []
def case(name, samples):
    for i, (time, exposure, automatic, mass, threshold) in enumerate(samples):
        rows.append(','.join(map(str, (name, i, time, exposure, int(automatic), mass, threshold))))
def masses(name, values, exposure=1000, automatic=False, step=1000, threshold=.5):
    case(name, [(i*step, exposure, automatic, value, threshold) for i,value in enumerate(values)])
masses('boundaries', [100]*6+[50,150,150.0001,49.9999,200,100])
masses('clouds-and-recovery', [100]*20+[100-i*1.5 for i in range(40)]+[30]*60+[60,60.001,100]*20+[100]*80)
masses('zero-tolerance', [100]*6+[99,100,101]*20, threshold=0)
masses('full-tolerance', [100]*6+[0,100,200,300,301]*20, threshold=1)
case('manual-exposure', [(i*1000,1000 if i<12 else 2000,False,100 if i<12 else 200,.5) for i in range(35)])
case('auto-exposure', [(i*1000,1000 if i%2 else 2000,True,(100 if i%2 else 200)*(3 if i==20 else 1),.5) for i in range(60)])
case('exposure-mode', [(i*1000,1000,i>=12,100,.5) for i in range(35)])
case('gap-and-window-edge', [(i*1000,1000,False,100,.5) for i in range(6)]+[(5000,1000,False,101,.5),(45000,1000,False,50,.5),(45001,1000,False,50,.5),(120000,1000,False,-1,.5),(121000,1000,False,200,.5),(122000,1000,False,200,.5)])
rng = random.Random(585)
masses('dense-history', [100+rng.randint(-10,10)+(200 if i%73==0 else 0) for i in range(1200)], step=80)
header='case,index,timeMs,exposureMs,automatic,mass,threshold,rejected,low,median,high,spike\n'
with tempfile.TemporaryDirectory(prefix='phd2-mass-') as directory:
    cpp=Path(directory)/'reference.cpp';exe=Path(directory)/('reference.exe' if sys.platform=='win32' else 'reference')
    cpp.write_text(shim+body+main,encoding='utf-8')
    subprocess.run(['g++','-std=c++11','-O2',str(cpp),'-o',str(exe)],check=True)
    output=subprocess.check_output([str(exe)],input='\n'.join(rows)+'\n',text=True)
resource=root/'app/src/test/resources/guide'
(resource/'phd2-mass-reference.csv').write_text(header+output,encoding='utf-8')
(resource/'phd2-mass-reference.json').write_text(json.dumps({'commit':commit,'samples':len(rows),'sourceSha256':hashlib.sha256(source).hexdigest(),'method':'Unmodified MassChecker class; UTC millisecond clock injected; no application code'},indent=2)+'\n',encoding='utf-8')
print('Generated',len(rows),'upstream mass-check samples')
