using System;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using Org.BouncyCastle.Crypto.Agreement.Srp;
using Org.BouncyCastle.Crypto.Digests;
using Org.BouncyCastle.Security;

namespace DeskPadPC
{
    internal static class PinPairingTests
    {
        private static int _checks, _exports;
        private static DateTime _now = new DateTime(2026, 11, 14, 0, 0, 0, DateTimeKind.Utc);
        private static readonly byte[] Fingerprint = Enumerable.Range(32,32).Select(i => (byte)i).ToArray();
        private static readonly string DummyCode = "DP2:" + PinWire.Encode(Enumerable.Range(0,64).Select(i => (byte)i).ToArray());
        private static void Check(bool value, string message) { _checks++; if (!value) throw new Exception(message); }
        private static void Reject(Action action, string message)
        { bool rejected=false; try { action(); } catch (ArgumentException) { rejected=true; } catch (InvalidOperationException) { rejected=true; } Check(rejected,message); }
        private static PinPairingController Controller()
        { _exports=0; return new PinPairingController(() => { _exports++; return DummyCode; },Fingerprint,() => _now); }
        private static Dictionary<string,object> Wire(Dictionary<string,object> value)
        { return PinWire.Parse(new JavaScriptSerializer().Serialize(value)); }
        private sealed class Peer
        {
            private readonly Srp6Client _client = new Srp6Client();
            private readonly string _id, _certificate;
            public readonly Dictionary<string,object> Proof;
            public Peer(Dictionary<string,object> challenge, string pin, byte[] fingerprint = null)
            {
                _id=(string)challenge["id"]; _certificate=PinWire.Hex(fingerprint ?? Fingerprint);
                _client.Init(Srp6StandardGroups.rfc5054_3072,new Sha256Digest(),new SecureRandom());
                byte[] identity=Encoding.UTF8.GetBytes("DeskPad-PIN-v1|"+_certificate+"|"+_id);
                var a=_client.GenerateClientCredentials(PinWire.Decode((string)challenge["salt"],16),identity,Encoding.ASCII.GetBytes(pin));
                _client.CalculateSecret(new Org.BouncyCastle.Math.BigInteger(1,PinWire.Decode((string)challenge["b"],384)));
                Proof=Wire(new Dictionary<string,object> { {"t","pin_proof"},{"v",2},{"id",_id},{"a",PinWire.Encode(PinWire.Pad(a,384))},{"m1",PinWire.Encode(PinWire.Pad(_client.CalculateClientEvidenceMessage(),32))} });
            }
            public Dictionary<string,object> Confirm(Dictionary<string,object> ready)
            {
                if (!_client.VerifyServerEvidenceMessage(new Org.BouncyCastle.Math.BigInteger(1,PinWire.Decode((string)ready["m2"],32)))) throw new Exception("Dummy peer rejected server evidence.");
                byte[] key=PinWire.Pad(_client.CalculateSessionKey(),32), proof;
                using(var hmac=new HMACSHA256(key)) proof=hmac.ComputeHash(Encoding.ASCII.GetBytes("DeskPad-PIN-v1-confirm|"+_id+"|"+_certificate));
                Array.Clear(key,0,key.Length);
                return Wire(new Dictionary<string,object> { {"t","pin_confirm"},{"v",2},{"id",_id},{"proof",PinWire.Encode(proof)} });
            }
        }
        public static int Main()
        { PinPairingSupport.Initialize(); return Run(); }
        [MethodImpl(MethodImplOptions.NoInlining)]
        private static int Run()
        {
            var library=AppDomain.CurrentDomain.GetAssemblies().Single(a => a.GetName().Name=="BouncyCastle.Cryptography");
            Check(string.IsNullOrEmpty(library.Location),"PIN library must load from verified embedded bytes.");
            using(var controller=Controller())
            {
                Check(!controller.IsOpen,"PIN enrollment must start closed.");
                Reject(() => controller.Begin(),"Enrollment accepted without an explicit local window.");
                string pin=controller.Open();
                Check(pin.Length==6 && pin.All(c => c>='0' && c<='9'),"PIN must contain exactly six ASCII digits.");
                Check(controller.RemainingAttempts==5 && controller.ExpiresUtc==_now.AddMinutes(2),"Enrollment limits differ from the approved policy.");
                using(var session=controller.Begin())
                {
                    var challenge=Wire(session.Challenge);
                    Check(controller.RemainingAttempts==4,"Attempt must be consumed before SRP work.");
                    Check(PinWire.Decode((string)challenge["id"],32).Length==32 && PinWire.Decode((string)challenge["salt"],16).Length==16,"Fresh challenge encoding is incorrect.");
                    Check(PinWire.Decode((string)challenge["b"],384).Length==384,"Server public value must use fixed-width3072-bit encoding.");
                    var peer=new Peer(challenge,pin);
                    var ready=Wire(session.VerifyClientProof(peer.Proof));
                    Check(_exports==0,"Credential was exported before client confirmation.");
                    var confirmation=peer.Confirm(ready);
                    Check(session.ConfirmAndGetCode(confirmation)==DummyCode && _exports==1,"Valid mutually authenticated PIN enrollment did not provision the existing credential.");
                    Check(!controller.IsOpen && controller.RemainingAttempts==0,"Successful enrollment must close the PIN window.");
                    Reject(() => session.ConfirmAndGetCode(confirmation),"A successful confirmation was replayed.");
                    Check(_exports==1,"Replay exported another credential.");
                }
            }
            using(var controller=Controller())
            {
                string pin=controller.Open(); using(var session=controller.Begin())
                {
                    string wrong=(pin[0]=='0' ? "1" : "0")+pin.Substring(1);
                    var peer=new Peer(session.Challenge,wrong);
                    Reject(() => session.VerifyClientProof(peer.Proof),"Wrong PIN was accepted.");
                    Check(_exports==0 && controller.IsOpen && controller.RemainingAttempts==4,"Wrong PIN leaked a credential or reset the global budget.");
                }
            }
            using(var controller=Controller())
            {
                string pin=controller.Open(); using(var session=controller.Begin())
                {
                    byte[] changed=(byte[])Fingerprint.Clone(); changed[0]^=1;
                    var peer=new Peer(session.Challenge,pin,changed);
                    Reject(() => session.VerifyClientProof(peer.Proof),"Different TLS leaf certificate binding was accepted.");
                    Check(_exports==0,"Certificate substitution leaked the credential.");
                }
            }
            foreach(bool useModulus in new[]{false,true})
                using(var controller=Controller())
                {
                    string pin=controller.Open(); using(var session=controller.Begin())
                    {
                        var proof=new Peer(session.Challenge,pin).Proof;
                        proof["a"]=PinWire.Encode(useModulus ? PinWire.Pad(Srp6StandardGroups.rfc5054_3072.N,384) : new byte[384]);
                        Reject(() => session.VerifyClientProof(proof),"Invalid public A (0 or N) was accepted.");
                        Check(_exports==0,"An invalid public value leaked a credential.");
                    }
                }
            using(var controller=Controller())
            {
                string pin=controller.Open(); using(var session=controller.Begin())
                {
                    var peer=new Peer(session.Challenge,pin); var ready=session.VerifyClientProof(peer.Proof); var confirm=peer.Confirm(ready);
                    confirm["proof"]=PinWire.Encode(new byte[32]);
                    Reject(() => session.ConfirmAndGetCode(confirm),"Invalid final confirmation MAC was accepted.");
                    Check(_exports==0,"Bad confirmation MAC leaked the credential.");
                }
            }
            using(var controller=Controller())
            {
                string pin=controller.Open(); using(var session=controller.Begin())
                {
                    var confirm=new Dictionary<string,object>{{"t","pin_confirm"},{"v",2},{"id",session.Challenge["id"]},{"proof",PinWire.Encode(new byte[32])}};
                    Reject(() => session.ConfirmAndGetCode(confirm),"Out-of-order confirmation was accepted.");
                    Check(_exports==0,"Out-of-order enrollment leaked the credential.");
                }
            }
            foreach(int mode in new[]{0,1,2})
                using(var controller=Controller())
                {
                    string pin=controller.Open(); using(var session=controller.Begin())
                    {
                        var peer=new Peer(session.Challenge,pin); var confirm=peer.Confirm(session.VerifyClientProof(peer.Proof));
                        if(mode==0) _now=_now.AddSeconds(121); else if(mode==1) controller.Close(); else controller.Open();
                        Reject(() => session.ConfirmAndGetCode(confirm),"Expired, closed or replaced PIN session remained valid.");
                        Check(_exports==0,"Invalidated enrollment leaked a credential.");
                    }
                }
            using(var controller=Controller())
            {
                controller.Open(); using(var session=controller.Begin())
                {
                    Reject(() => controller.Begin(),"More than one PIN session was allowed in flight.");
                    Check(controller.RemainingAttempts==4,"Rejected parallel session changed the active attempt budget.");
                }
            }
            using(var controller=Controller())
            {
                controller.Open();
                for(int i=0;i<5;i++) using(var session=controller.Begin()) { }
                Check(!controller.IsOpen && controller.RemainingAttempts==0,"Five disconnected attempts did not close enrollment.");
                Reject(() => controller.Begin(),"Exhausted global attempt budget was bypassed.");
            }
            using(var controller=Controller())
            {
                controller.Open(); var start=new ManualResetEvent(false); PinPairingSession first=null, second=null;
                var a=new Thread(() => { start.WaitOne(); try { first=controller.Begin(); } catch(InvalidOperationException) {} });
                var b=new Thread(() => { start.WaitOne(); try { second=controller.Begin(); } catch(InvalidOperationException) {} });
                a.Start();b.Start();start.Set();a.Join();b.Join();start.Dispose();
                Check((first==null)!=(second==null),"Concurrent begin calls bypassed the single-session limit.");
                Check(controller.RemainingAttempts==4,"Concurrent begin calls consumed an unexpected number of attempts.");
                if(first!=null) first.Dispose(); if(second!=null) second.Dispose();
            }
            Check(PinWire.Parse(" { \"t\" : \"pin_begin\", \"v\" : 2 } ")["t"].Equals("pin_begin"),"Valid ASCII enrollment frame was rejected.");
            foreach(string invalid in new[]{
                "{\"t\":\"pin_begin\",\"v\":2,\"v\":2}","{\"t\":\"pin_begin\",\"v\":2,\"extra\":2}",
                "{\"t\":\"pin_begin\",\"v\":2.0}","{\"t\":\"pin_begin\",\"v\":\"2\"}",
                "{\"t\":\"pin_begin\",\"v\":02}","{\"t\":\"pin_begin\",\"v\":2}{}",
                "{\"t\":\"pin_begin\",\"v\":2,}","{\"t\":\"pin_begin\",\"v\":[]}",
                "{\"t\":\"pin_begin\",\"v\":null}","{\"t\":\"pin_begin\",\"\\u0076\":2}",
                "{\"t\":\"pin_\\u0062egin\",\"v\":2}","{\"t\":\"pin_begin\",\"v\":2,\"pin\":\"123456\"}",
                "{\"t\":\"unknown\",\"v\":2}","{\"t\":\"pín_begin\",\"v\":2}",new string(' ',2049)})
                Reject(() => PinWire.Parse(invalid),"Malformed, ambiguous or unknown PIN JSON was accepted.");
            Reject(() => PinWire.Decode(PinWire.Encode(new byte[32])+"=",32),"Padded base64url was accepted.");
            Reject(() => PinWire.Decode(new string('A',42)+"B",32),"Noncanonical base64url trailing bits were accepted.");
            Console.WriteLine("PASS: "+_checks+" PIN enrollment checks; synthetic identities and recording-free unit peers only.");
            return 0;
        }
    }
}
