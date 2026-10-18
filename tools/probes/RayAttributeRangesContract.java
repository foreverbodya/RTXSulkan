import com.sulkan.shaders.render.raytracing.RayAttributeRanges;
import java.util.ArrayList;
import java.util.Random;

public final class RayAttributeRangesContract {
    public static void main(String[] args) {
        var arena=new RayAttributeRanges(1024);
        RayAttributeRanges.Allocation a=arena.allocate(1),b=arena.allocate(17),c=arena.allocate(32);
        if(a.offset()!=0||a.bytes()!=16||b.offset()!=16||b.bytes()!=32||c.offset()!=48)throw new AssertionError("Alignment differs");
        arena.release(b);
        var reused=arena.allocate(20);
        if(reused.offset()!=b.offset()||reused.serial()==b.serial())throw new AssertionError("Range reuse differs");
        rejected(()->arena.release(b));
        if(arena.usedBytes()!=80)throw new AssertionError("Stale release changed ownership");
        arena.release(a);arena.release(c);arena.release(reused);
        var full=arena.allocate(1024);
        if(arena.tryAllocate(1)!=null || arena.usedBytes()!=1024)throw new AssertionError("Failed tryAllocate changed ownership");
        rejected(()->arena.allocate(1));arena.release(full);
        Random random=new Random(6731);
        var live=new ArrayList<RayAttributeRanges.Allocation>();
        for(int step=0;step<10000;step++) {
            if(!live.isEmpty()&&random.nextBoolean())arena.release(live.remove(random.nextInt(live.size())));
            else {
                try {
                    var next=arena.allocate(1+random.nextInt(120));
                    for(var old:live)if(next.offset()<old.offset()+old.bytes()&&old.offset()<next.offset()+next.bytes())throw new AssertionError("Live ranges overlap");
                    live.add(next);
                }catch(IllegalStateException fullArena) { if(!fullArena.getMessage().contains("exhausted"))throw fullArena; }
            }
            if(arena.usedBytes()!=live.stream().mapToInt(RayAttributeRanges.Allocation::bytes).sum()||arena.allocations()!=live.size())throw new AssertionError("Accounting differs");
        }
        live.forEach(arena::release);
        if(arena.usedBytes()!=0||arena.allocate(1024).offset()!=0)throw new AssertionError("Free ranges did not coalesce");
        System.out.println("SULKAN_RAY_ATTRIBUTE_RANGES_PASS steps=10000 alignment=true reuse=true staleReleaseRejected=true bounds=true accounting=true coalescing=true");
    }
    private static void rejected(Runnable operation) {
        try{operation.run();}catch(IllegalStateException expected){return;}
        throw new AssertionError("Invalid operation accepted");
    }
}
