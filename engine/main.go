// Framed IP packets on stdin/stdout connect Android VpnService to a userspace
// IP stack. Only the two DNS interface addresses are routed into this tunnel.
package main

import (
 "bufio"
 "encoding/binary"
 "fmt"
 "io"
 "net"
 "net/netip"
 "os"
 "time"
 "golang.zx2c4.com/wireguard/tun/netstack"
)

func readFrame(r io.Reader) ([]byte,error) {
 var h [2]byte
 if _,e:=io.ReadFull(r,h[:]);e!=nil{return nil,e}
 b:=make([]byte,binary.BigEndian.Uint16(h[:])); _,e:=io.ReadFull(r,b);return b,e
}
func writeFrame(w io.Writer,b []byte) error {
 h:=[]byte{byte(len(b)>>8),byte(len(b))}
 if _,e:=w.Write(h);e!=nil{return e};_,e:=w.Write(b);return e
}
func exchange(q []byte, servers []string, proto string) []byte {
 if len(q)<12{return nil}
 for _,s:=range servers {
  conn,e:=net.DialTimeout(proto,s,1500*time.Millisecond);if e!=nil{continue}
  conn.SetDeadline(time.Now().Add(2*time.Second))
  var reply []byte
  if proto=="tcp" {
   if e=writeFrame(conn,q);e==nil{reply,e=readFrame(conn)}
  } else {
   if _,e=conn.Write(q);e==nil{buf:=make([]byte,65535);var n int;n,e=conn.Read(buf);reply=buf[:n]}
  }
  conn.Close()
  if e==nil && len(reply)>=12 && reply[0]==q[0] && reply[1]==q[1] && reply[2]&128!=0 {return reply}
 }
 // SERVFAIL, with zero answer/authority/additional records.
 b:=make([]byte,12);copy(b,q[:2]);b[2]=128|(q[2]&1);b[3]=130;return b
}
func run() error {
 ips:=[]netip.Addr{netip.MustParseAddr("192.0.2.53"),netip.MustParseAddr("fd42:53::53")}
 servers:=[]string{}
 for _,s:=range os.Args[1:]{a,e:=netip.ParseAddr(s);if e!=nil{return e};servers=append(servers,net.JoinHostPort(a.String(),"53"))}
 if len(servers)==0{return fmt.Errorf("no DNS server")}
 dev,stack,e:=netstack.CreateNetTUN(ips,nil,1500);if e!=nil{return e}
 limit:=make(chan struct{},96)
 for _,ip:=range ips {
  addr:=netip.AddrPortFrom(ip,53)
  udp,e:=stack.ListenUDPAddrPort(addr);if e!=nil{return e}
  go func(pc net.PacketConn){for{buf:=make([]byte,65535);n,peer,e:=pc.ReadFrom(buf);if e!=nil{return}
   select{case limit<-struct{}{}:go func(q []byte,to net.Addr){defer func(){<-limit}();if b:=exchange(q,servers,"udp");b!=nil{pc.WriteTo(b,to)}}(buf[:n],peer);default:}
  }}(udp)
  tcp,e:=stack.ListenTCPAddrPort(addr);if e!=nil{return e}
  go func(l net.Listener){for{c,e:=l.Accept();if e!=nil{return};select{case limit<-struct{}{}:go func(c net.Conn){defer c.Close();defer func(){<-limit}()
   for{c.SetDeadline(time.Now().Add(15*time.Second));q,e:=readFrame(c);if e!=nil{return};b:=exchange(q,servers,"tcp");if b==nil||writeFrame(c,b)!=nil{return}}
  }(c);default:c.Close()}}}(tcp)
 }
 go func(){buf:=[][]byte{make([]byte,65535)};sizes:=make([]int,1);out:=bufio.NewWriter(os.Stdout)
  for{_,e:=dev.Read(buf,sizes,0);if e!=nil||writeFrame(out,buf[0][:sizes[0]])!=nil||out.Flush()!=nil{os.Exit(1)}}
 }()
 input:=bufio.NewReader(os.Stdin)
 for{b,e:=readFrame(input);if e!=nil{return e};if len(b)>0{if _,e=dev.Write([][]byte{b},0);e!=nil{return e}}}
}
func main(){if e:=run();e!=nil && e!=io.EOF{fmt.Fprintln(os.Stderr,e);os.Exit(1)}}
