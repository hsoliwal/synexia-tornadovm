package com.m3.tornado.runtime.graal.nodes;

import com.m3.tornado.graal.compiler.core.common.type.StampFactory;
import com.m3.tornado.graal.compiler.graph.NodeClass;
import com.m3.tornado.graal.compiler.nodeinfo.NodeInfo;
import com.m3.tornado.graal.compiler.nodes.FrameState;
import com.m3.tornado.graal.compiler.nodes.ValueNode;
import com.m3.tornado.graal.compiler.nodes.calc.FloatingNode;

import static com.m3.tornado.graal.compiler.nodeinfo.InputType.State;

@NodeInfo(nameTemplate = "AtomicWriteExtension")
public class WriteAtomicNodeExtension extends FloatingNode {

    public static final NodeClass<WriteAtomicNodeExtension> TYPE = NodeClass.create(WriteAtomicNodeExtension.class);

    @OptionalInput(State)
    FrameState stateAfter;
    @OptionalInput
    ValueNode extraOperation;
    @OptionalInput
    ValueNode startNode;

    public WriteAtomicNodeExtension(ValueNode startNode) {
        super(TYPE, StampFactory.forVoid());
        this.startNode = startNode;
    }

    public FrameState getStateAfter() {
        return stateAfter;
    }

    public void setStateAfter(FrameState stateAfter) {
        this.stateAfter = stateAfter;
    }

    public ValueNode getExtraOperation() {
        return extraOperation;
    }

    public ValueNode getStartNode() {
        return startNode;
    }

    public void setExtraOperation(ValueNode extraOperation) {
        this.extraOperation = extraOperation;
    }

}
