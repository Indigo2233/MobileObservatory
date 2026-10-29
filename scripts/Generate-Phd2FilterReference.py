"""Compile unmodified PHD2 result() bodies against small collection/statistics shims.

Usage: python scripts/Generate-Phd2FilterReference.py PHD2_SOURCE_DIRECTORY
The source commit is pinned in docs/GUIDING_PHD2_ALIGNMENT.md. No application code is imported.
"""
import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile

root = pathlib.Path(__file__).resolve().parents[1]
upstream = pathlib.Path(sys.argv[1])
commit = subprocess.check_output(["git", "-C", str(upstream), "rev-parse", "HEAD"], text=True).strip()
assert commit == "70e9fd178b3122b6553646c60f462cb09335dcc5", commit
shim = r'''
#include <algorithm>
#include <cmath>
#include <iomanip>
#include <iostream>
#include <string>
#include <vector>
#include "zfilterfactory.h"
struct wxString : std::string { using std::string::string; template<class... A> static wxString Format(const char*, A...) { return wxString(); } };
#define THROW_INFO(x) wxString(x)
#define POSSIBLY_UNUSED(x) (void)(x)
struct Logger { void Write(const wxString&) {} } Debug;
static int sign(double x) { return (x > 0) - (x < 0); }
struct History : std::vector<double> {
    void Add(double v) { push_back(v); } void RemoveAt(int i) { erase(begin()+i); }
    size_t GetCount() const { return size(); }
};
struct Stats {
    std::vector<double> values; int capacity;
    Stats(int c=0):capacity(c) {}
    void AddGuideInfo(double, double v, double) { values.push_back(v); if(capacity && values.size()>size_t(capacity)) RemoveOldestEntry(); }
    void RemoveOldestEntry() { values.erase(values.begin()); }
    void ClearAll() { values.clear(); } size_t GetCount() const { return values.size(); }
    double GetMedian() const { auto s=values; std::sort(s.begin(),s.end()); return s[s.size()/2]; }
    void GetLinearFitResults(double* slope, double* intercept) const {
        double c=(values.size()-1)/2., mean=0, xy=0, xx=0;
        for(double v:values) mean+=v; mean/=values.size();
        for(size_t i=0;i<values.size();++i) { xy+=(i-c)*(values[i]-mean); xx+=(i-c)*(i-c); }
        *slope=xx ? xy/xx : 0; *intercept=mean-*slope*c;
    }
};
struct GuideAlgorithmHysteresis { double m_lastMove=0,m_hysteresis=.1,m_aggression=.7,m_minMove=.2; double result(double); };
struct GuideAlgorithmIdentity { double result(double); };
struct GuideAlgorithmZFilter {
    double m_gain,m_minMove=.2,m_sumCorr=0;std::vector<double> m_xv,m_yv,m_xcoeff,m_ycoeff;
    GuideAlgorithmZFilter() { ZFilterFactory f(BESSEL,4,8);m_gain=f.gain();m_xcoeff=f.xcoeffs;m_ycoeff=f.ycoeffs;m_xv.resize(m_xcoeff.size());m_yv.resize(m_ycoeff.size()); }
    double result(double);
};
struct GuideAlgorithmLowpass {
    static const unsigned HISTORY_SIZE=10; Stats m_axisStats; int m_timeBase=0; double m_slopeWeight=5,m_minMove=.2;
    GuideAlgorithmLowpass() { for(int i=0;i<10;++i) m_axisStats.AddGuideInfo(m_timeBase++,0,0); } double result(double);
};
struct GuideAlgorithmLowpass2 {
    static const unsigned HISTORY_SIZE=10; Stats m_axisStats{10}; int m_timeBase=0,m_rejects=0; double m_aggressiveness=70,m_minMove=.2;
    void reset() { m_axisStats.ClearAll();m_timeBase=0;m_rejects=0; } double result(double);
};
struct GuideAlgorithmResistSwitch {
    static const unsigned HISTORY_SIZE=10; History m_history; int m_currentSide=0; double m_minMove=.2,m_aggression=.7; bool m_fastSwitchEnabled=true;
    GuideAlgorithmResistSwitch() { for(int i=0;i<10;++i) m_history.Add(0); } double result(double);
};
'''
sources = {}
for filename, classname in [
    ("guide_algorithm_hysteresis.cpp", "GuideAlgorithmHysteresis"),
    ("guide_algorithm_lowpass.cpp", "GuideAlgorithmLowpass"),
    ("guide_algorithm_lowpass2.cpp", "GuideAlgorithmLowpass2"),
    ("guide_algorithm_resistswitch.cpp", "GuideAlgorithmResistSwitch"),
    ("guide_algorithm_zfilter.cpp", "GuideAlgorithmZFilter"),
    ("guide_algorithm_identity.cpp", "GuideAlgorithmIdentity"),
]:
    data = (upstream / "src" / filename).read_bytes()
    sources[filename] = hashlib.sha256(data.replace(b"\r\n", b"\n")).hexdigest()
    text = data.decode()
    start = text.index(f"double {classname}::result(double input)")
    brace = text.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    shim += "\n" + text[start:end] + "\n"

shim += r'''
template<class T> void run(const char* name,T& f,bool fast) {
    const double samples[]={0,.1,.19,.2,.21,.25,.3,.35,.4,.45,.5,.4,.3,.1,-.1,-.21,-.22,-.24,-.28,-.3,-.4,-.61,0,1.2,.1,-1.3,.2,0};
    int i=0; for(double v:samples) std::cout<<name<<","<<fast<<","<<i++<<","<<v<<","<<f.result(v)<<"\n";
}
int main() {
    std::cout<<std::setprecision(17)<<"algorithm,fastSwitch,index,inputPx,outputPx\n";
    GuideAlgorithmHysteresis h; run("HYSTERESIS",h,true);
    GuideAlgorithmLowpass l; run("LOW_PASS",l,true);
    GuideAlgorithmLowpass2 l2; run("LOW_PASS_2",l2,true);
    GuideAlgorithmResistSwitch r; run("RESIST_SWITCH",r,true);
    GuideAlgorithmResistSwitch slow; slow.m_fastSwitchEnabled=false; run("RESIST_SWITCH",slow,false);
    GuideAlgorithmZFilter z; run("Z_FILTER",z,true);
    GuideAlgorithmIdentity identity; run("IDENTITY",identity,true);
}
'''
with tempfile.TemporaryDirectory(prefix="phd2-reference-") as temp:
    temp = pathlib.Path(temp)
    cpp = temp / "reference.cpp"
    exe = temp / ("reference.exe" if sys.platform == "win32" else "reference")
    cpp.write_text(shim)
    (temp / "phd.h").write_text('#include <string>\n#include <stdexcept>\n#include <algorithm>\n#define ERROR_INFO(message) std::runtime_error(message)\n')
    for name in ("zfilterfactory.cpp", "zfilterfactory.h"):
        data = (upstream / "src" / name).read_bytes()
        sources[name] = hashlib.sha256(data.replace(b"\r\n", b"\n")).hexdigest()
        (temp / name).write_bytes(data)
    subprocess.run(["g++", "-std=c++11", "-O1", "-DM_PI=3.14159265358979323846", str(cpp), str(temp / "zfilterfactory.cpp"), "-o", str(exe)], check=True)
    output = subprocess.check_output([str(exe)], text=True)
destination = root / "app/src/test/resources/guide/phd2-filter-reference.csv"
destination.write_text(output, newline="\n")
destination.with_suffix(".json").write_text(json.dumps({"commit": commit, "source_newlines": "LF", "sources": sources}, indent=2) + "\n")
print(f"Wrote {len(output.splitlines())-1} upstream reference samples to {destination}")
