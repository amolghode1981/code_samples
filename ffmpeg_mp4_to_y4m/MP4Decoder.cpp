#include "MP4Handler.h"
#include <iostream>
using namespace std;
int main() {
    string s = "sample.mp4";
    string output = "/tmp/output.y4m";
    MP4Handler handler;
    handler.openFile(s);
    handler.allocateOutputFormat(output);
    handler.transcodePackets();
    handler.cleanup();
    cout << "End" << endl;
}

