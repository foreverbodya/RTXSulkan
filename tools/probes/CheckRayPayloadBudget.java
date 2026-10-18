import com.sulkan.shaders.render.raytracing.RayPayloadBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

public final class CheckRayPayloadBudget {
    static void check(boolean value) {if(!value)throw new AssertionError("Budget invariant changed");}
    public static void main(String[] args)throws Exception {
        Path report=Path.of(args[0]);check(!Files.exists(report));
        long gib=1024L*1024*1024;
        for(int heap:new int[]{1,2,4,8,12,16,24})
            check(RayPayloadBudget.defaultBytes(heap*gib)==Math.min(3*gib,heap*gib/4));
        var large=new RayPayloadBudget(3*gib);Object old=new Object(),next=new Object();
        large.register(old);large.register(next);
        check(large.reserve(old,3*gib)==RayPayloadBudget.Result.RESERVED);
        check(large.reserve(next,64*1024*1024)==RayPayloadBudget.Result.WAIT_FOR_PEER);
        check(large.snapshot().reservedBytes()==3*gib);
        large.release(old,3*gib);large.unregister(old);
        check(large.reserve(next,3*gib)==RayPayloadBudget.Result.RESERVED);
        check(large.reserve(next,1)==RayPayloadBudget.Result.HARD_LIMIT);
        large.release(next,3*gib);large.unregister(next);check(large.snapshot().reservedBytes()==0);
        var budget=new RayPayloadBudget(6400);Object[] owners={new Object(),new Object(),new Object(),new Object()};
        for(var owner:owners)budget.register(owner);
        long[] owned=new long[owners.length];long total=0;int waits=0,limits=0;
        var random=new Random(672026);
        for(int i=0;i<50000;i++) {
            int owner=random.nextInt(owners.length);long bytes=(1+random.nextInt(20))*64L;
            if(random.nextBoolean() && owned[owner]>0) {
                bytes=Math.min(bytes,owned[owner]);budget.release(owners[owner],bytes);owned[owner]-=bytes;total-=bytes;
            } else {
                var expected=bytes>6400-owned[owner]?RayPayloadBudget.Result.HARD_LIMIT:
                    bytes>6400-total?(total>owned[owner]?RayPayloadBudget.Result.WAIT_FOR_PEER:RayPayloadBudget.Result.HARD_LIMIT):RayPayloadBudget.Result.RESERVED;
                var result=budget.reserve(owners[owner],bytes);check(result==expected);
                if(result==RayPayloadBudget.Result.RESERVED){owned[owner]+=bytes;total+=bytes;}
                if(result==RayPayloadBudget.Result.WAIT_FOR_PEER)waits++;
                if(result==RayPayloadBudget.Result.HARD_LIMIT)limits++;
            }
            check(budget.snapshot().reservedBytes()==total && total>=0 && total<=6400);
            for(int j=0;j<owners.length;j++)check(budget.ownedBytes(owners[j])==owned[j]);
        }
        check(waits>0);
        for(int i=0;i<owners.length;i++) {
            if(owned[i]>0)budget.release(owners[i],owned[i]);budget.unregister(owners[i]);
        }
        check(budget.snapshot().reservedBytes()==0 && budget.snapshot().arenas()==0);
        boolean stale=false;try{budget.release(owners[0],1);}catch(IllegalStateException expected){stale=true;}check(stale);
        String json="{\"passed\":true,\"operations\":50000,\"peerWaits\":"+waits+",\"hardLimits\":"+limits+",\"threeGiBAccounting\":true,\"nonMutatingFailedReservation\":true,\"scope\":\"CPU page ownership and heap-fraction policy; GPU allocation and retirement tested separately\"}\n";
        Files.writeString(report,json);System.out.print(json);
    }
}
