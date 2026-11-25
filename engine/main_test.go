package main
import("testing";"net";"time";"bytes";"io")
func TestForward(t *testing.T){
 q:=[]byte{12,23,1,0,0,1,0,0,0,0,0,0,1,97,0,0,1,0,1}
 want:=append([]byte{},q...);want[2]=129
 for _,proto:=range []string{"udp","tcp"}{t.Run(proto,func(t *testing.T){
 var addr string
 if proto=="udp"{c,e:=net.ListenPacket("udp","127.0.0.1:0");if e!=nil{t.Fatal(e)};defer c.Close();addr=c.LocalAddr().String()
 go func(){b:=make([]byte,512);c.SetDeadline(time.Now().Add(time.Second*5));n,p,e:=c.ReadFrom(b);if e==nil&&bytes.Equal(b[:n],q){c.WriteTo(want,p)}}()
 }else{l,e:=net.Listen("tcp","127.0.0.1:0");if e!=nil{t.Fatal(e)};defer l.Close();addr=l.Addr().String()
 go func(){c,e:=l.Accept();if e!=nil{return};defer c.Close();b,e:=readFrame(c);if e==nil&&bytes.Equal(b,q){writeFrame(c,want)}}()}
 got:=exchange(q,[]string{addr},proto);if !bytes.Equal(got,want){t.Fatal(got)}
 })}
 if b:=exchange(q,nil,"udp");len(b)!=12 || b[3]&15!=2 {t.Fatal("missing SERVFAIL")}
 if _,e:=readFrame(bytes.NewReader([]byte{0,4,1}));e!=io.ErrUnexpectedEOF{t.Fatal(e)}
}
