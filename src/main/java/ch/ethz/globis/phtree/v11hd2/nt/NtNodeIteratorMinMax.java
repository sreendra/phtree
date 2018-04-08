/*
 * Copyright 2011-2016 ETH Zurich. All Rights Reserved.
 *
 * This software is the proprietary information of ETH Zurich.
 * Use is subject to license terms.
 */
package ch.ethz.globis.phtree.v11hd2.nt;

import ch.ethz.globis.pht64kd.MaxKTreeHdI.NtEntry;
import ch.ethz.globis.phtree.v11hd.BitsHD;

/**
 * Iterator over a NodeTree.
 * 
 * This NodeIterator reuses existing instances, which may be easier on the Java GC.
 * 
 * @author ztilmann
 *
 * @param <T> value type
 */
public class NtNodeIteratorMinMax<T> {
	
	private static final long FINISHED = Long.MAX_VALUE; 
	private static final long START = -1; 
	
	private boolean isHC;
	private long next;
	private NtNode<T> nextSubNode;
	private NtNode<T> node;
	private int currentOffsetKey;
	private int nMaxEntry;
	private int nFound = 0;
	private int postEntryLenLHC;
	//The valTemplate contains the known prefix
	private final long[] prefix;
	private long localMin;
	private long localMax;
	private final long[] globalMin;
	private final long[] globalMax;

	/**
	 */
	public NtNodeIteratorMinMax(long[] globalMin, long[] globalMax) {
		//TODO?!?!? Reuse valTemplate???
		this.prefix = new long[globalMin.length];
		this.globalMin = globalMin;
		this.globalMax = globalMax;
	}
	
	/**
	 * 
	 * @param node
	 * @param globalMinMask The minimum value that any found value should have. If the found value is
	 *  lower, the search continues.
	 * @param globalMaxMask
	 * @param prefix
	 */
	private void reinit(NtNode<T> node, long[] valTemplate) {
		BitsHD.set(prefix, valTemplate); //TODO
		next = START;
		nextSubNode = null;
		currentOffsetKey = 0;
		nFound = 0;
	
		this.node = node;
		this.isHC = node.isAHC();
		nMaxEntry = node.getEntryCount();
		//Position of the current entry
		currentOffsetKey = node.getBitPosIndex();
		if (!isHC) {
			//length of post-fix WITH key
			postEntryLenLHC = NtNode.IK_WIDTH(NtNode.MAX_DIM) + NtNode.MAX_DIM*node.getPostLen();
		}
	}

	/**
	 * Advances the cursor. 
	 * @return TRUE iff a matching element was found.
	 */
	boolean increment(NtEntry<T> result) {
		getNext(result);
		return next != FINISHED;
	}

	long getCurrentPos() {
		return next;
	}

	/**
	 * Return whether the next value returned by next() is a sub-node or not.
	 * 
	 * @return True if the current value (returned by next()) is a sub-node, 
	 * otherwise false
	 */
	boolean isNextSub() {
		return nextSubNode != null;
	}

	/**
	 * 
	 * @return False if the value does not match the range, otherwise true.
	 */
	@SuppressWarnings("unchecked")
	private boolean readValue(int pin, long pos, NtEntry<T> result) {
		Object v = node.getValueByPIN(pin);
		
		if (v == null) {
			return false;
		}
		
		node.localReadAndApplyReadPostfixAndHc(pin, pos, prefix);
		
		if (v instanceof NtNode) {
			NtNode<T> sub = (NtNode<T>) v;
			//TODO clean up
			if (!checkPrefix((sub.getPostLen()+1)*NtNode.MAX_DIM)) {
//			long mask = (-1L) << ((sub.getPostLen()+1)*NtNode.MAX_DIM);
//			if (prefix < (globalMin & mask) || (prefix & mask) > globalMax) {
				return false;
			}
			nextSubNode = sub;
		} else {
			if (!checkPrefix(0)) {
			//if (prefix < globalMin || prefix > globalMax) {
				return false;
			}
			nextSubNode = null;
			node.getKdKeyByPIN(pin, result.getKdKey());
			result.setValue(v == NodeTreeV11.NT_NULL ? null : (T)v);
		}
		result.setKey(prefix);
		return true;
	}

	//TODO we could also respect 'higher' bits to ignore -> Speed up
	private boolean checkPrefix(int bitsToIgnore) {
		int slotsToIgnore = BitsHD.div64(bitsToIgnore);
		for (int i = 0; i < prefix.length-slotsToIgnore-1; i++) {
			if (Long.compareUnsigned(prefix[i], globalMin[i]) < 0 
					|| Long.compareUnsigned(prefix[i], globalMax[i]) > 0) {
				return false;
			}
		}
		int i = prefix.length-slotsToIgnore-1;
//		long mask = (-1L) << ((sub.getPostLen()+1)*NtNode.MAX_DIM);
//		int bitsInLastSlotToIgnore = BitsHD.mod64(bitsToIgnore); 
//		long mask = bitsInLastSlotToIgnore == 0 ? -1L : (-1L) << bitsInLastSlotToIgnore;
		long mask = (-1L) << BitsHD.mod64(bitsToIgnore);
		return Long.compareUnsigned(prefix[i], (globalMin[i] & mask)) >= 0  
				&& Long.compareUnsigned((prefix[i] & mask), globalMax[i]) <= 0;
	}

	private void getNext(NtEntry<T> result) {
		if (isHC) {
			getNextAHC(result);
		} else {
			getNextLHC(result);
		}
	}
	
	private void getNextAHC(NtEntry<T> result) {
		long currentPos = next == START ? localMin : next+1; 
		while (currentPos <= localMax) {
			if (readValue((int)currentPos, currentPos, result)) {
				next = currentPos;
				return;
			}
			currentPos++;  //pos w/o bit-offset
		}
		next = FINISHED;
	}
	
	private void getNextLHC(NtEntry<T> result) {
		while (++nFound <= nMaxEntry) {
			long currentPos = 
					Bits.readArray(node.ba, currentOffsetKey, NtNode.IK_WIDTH(NtNode.MAX_DIM));
			currentOffsetKey += postEntryLenLHC;
			//check HC-pos
			if (currentPos <= localMax) {
				//check post-fix
				if (readValue(nFound-1, currentPos, result)) {
					next = currentPos;
					return;
				}
			} else {
				break;
			}
		}
		next = FINISHED;
	}
	

	public NtNode<T> getCurrentSubNode() {
		return nextSubNode;
	}

	public NtNode<T> node() {
		return node;
	}

	/**
	 * 
	 * @param globalMin
	 * @param globalMax
	 * @param prefix
	 * @param postLen
	 * @return 'false' if the new upper limit is smaller than the current HC-pos.
	 */
	boolean calcLimits(long[] prefix, boolean isNegativeRoot) {
		//create limits for the local node. there is a lower and an upper limit. Each limit
		//consists of a series of DIM bit, one for each dimension.
		//For the lower limit, a '1' indicates that the 'lower' half of this dimension does 
		//not need to be queried.
		//For the upper limit, a '0' indicates that the 'higher' half does not need to be 
		//queried.
		//
		//              ||  lowerLimit=0 || lowerLimit=1 || upperLimit = 0 || upperLimit = 1
		// =============||===================================================================
		// query lower  ||     YES             NO
		// ============ || ==================================================================
		// query higher ||                                     NO               YES
		//
		int postLen = node.getPostLen();
		if (isNegativeRoot) {
			//TODO this doesn't really work. Is there a better way that also keeps the ordering?
//			this.localMin = NtNode.pos2LocalPosNegative(globalMin, postLen);
//			this.localMax = NtNode.pos2LocalPosNegative(globalMax, postLen);
			this.localMin = 0;
			this.localMax = ~((-1L) << NtNode.MAX_DIM);
		} else {
			if (compareWithPrefixEquals0(globalMin, prefix, postLen)) {
				//TODO cleanup
			//if ((globalMin ^ prefix) >> postLen == 0) {
				this.localMin = NtNode.pos2LocalPos(globalMin, postLen);
			} else {
				this.localMin = 0;
			}
			if (compareWithPrefixEquals0(globalMax, prefix, postLen)) {
//			if ((globalMax ^ prefix) >> postLen == 0) {
				this.localMax = NtNode.pos2LocalPos(globalMax, postLen);
			} else {
				this.localMax = ~((-1L) << NtNode.MAX_DIM);
			}
		}
		//TODO
		//TODO
		//TODO
		//TODO
		//TODO
		//TODO
		//TODO
		//TODO
		if (localMin > localMax) {
			throw new IllegalStateException("localMin=" + localMin + " / " + localMax);
		}
		return true;
	}
	
	private boolean compareWithPrefixEquals0(long[] minMax, long[] prefix, int postLen) {
		int withMask = postLen == 0 ? 0 : (BitsHD.div64(postLen - 1) + 1);
		int iMax = prefix.length - withMask;
		for (int i = 0; i < iMax; i++) {
			if ((minMax[i] ^ prefix[i]) >> postLen != 0) {
				return false;
			}			
		}
		if (withMask > 0) {
			int shift = BitsHD.mod64(postLen);
			return ((minMax[iMax] ^ prefix[iMax]) >> shift == 0);
		}
		return true;
	}
	
	void init(long[] valTemplate, NtNode<T> node, boolean isNegativeRoot) {
		this.node = node; //for calcLimits
		calcLimits(valTemplate, isNegativeRoot);
		reinit(node, valTemplate);
	}

	public long[] getPrefix() {
		//TODO returning this is dangerous?!?!?
		return prefix;
	}
}
