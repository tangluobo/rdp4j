package com.tangluobo.tomato.rdp.jasn1.ber;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import com.tangluobo.tomato.rdp.jasn1.ber.types.BerBitString;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerBoolean;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerContextSpecific;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerDate;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerDateTime;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerDuration;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerEmbeddedPdv;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerEnum;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerGeneralizedTime;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerInteger;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerNull;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerObjectIdentifier;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerOctetString;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerReal;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerSequence;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerTime;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerTimeOfDay;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerType;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerUtcTime;

public class BerInputStream extends FilterInputStream {
	private boolean eof;

	public BerInputStream(InputStream in) {
		super(in);
	}

	@SuppressWarnings("unchecked")
	public <T extends BerType> T next(Class<T> typeClass) throws IOException {
		BerType t = next();
		if (typeClass.isAssignableFrom(t.getClass()))
			return (T) t;
		throw new IllegalArgumentException("Unexpected type");
	}

	public BerType next() throws IOException {
		BerTag tag = new BerTag();
		try {
			tag.decode(this);
		} catch (EOFException eofe) {
			if (eof)
				throw eofe;
			eof = true;
			return null;
		}
		BerType type = null;
		switch (tag.tagClass) {
		case BerTag.CONTEXT_CLASS:
			type = new BerContextSpecific();
			type.getTag().tagNumber = tag.tagNumber;
			type.decode(this, false);
			break;
		case BerTag.UNIVERSAL_CLASS:
			switch (tag.tagNumber) {
			case BerTag.BIT_STRING_TAG:
				type = new BerBitString();
				type.decode(this, false);
				break;
			case BerTag.BOOLEAN_TAG:
				type = new BerBoolean();
				type.decode(this, false);
				break;
			case BerTag.DATE_TAG:
				type = new BerDate();
				type.decode(this, false);
				break;
			case BerTag.DATE_TIME_TAG:
				type = new BerDateTime();
				type.decode(this, false);
				break;
			case BerTag.DURATION_TAG:
				type = new BerDuration();
				type.decode(this, false);
				break;
			case BerTag.EMBEDDED_PDV_TAG:
				type = new BerEmbeddedPdv();
				type.decode(this, false);
				break;
			case BerTag.ENUMERATED_TAG:
				type = new BerEnum();
				type.decode(this, false);
				break;
			case BerTag.GENERALIZED_TIME_TAG:
				type = new BerGeneralizedTime();
				type.decode(this, false);
				break;
			case BerTag.INTEGER_TAG:
				type = new BerInteger();
				type.decode(this, false);
				break;
			case BerTag.NULL_TAG:
				type = new BerNull();
				type.decode(this, false);
				break;
			case BerTag.OBJECT_IDENTIFIER_TAG:
				type = new BerObjectIdentifier();
				type.decode(this, false);
				break;
			case BerTag.OCTET_STRING_TAG:
				type = new BerOctetString();
				type.decode(this, false);
				break;
			case BerTag.REAL_TAG:
				type = new BerReal();
				type.decode(this, false);
				break;
			case BerTag.SEQUENCE_TAG:
				type = new BerSequence();
				type.decode(this, false);
				break;
			case BerTag.TIME_TAG:
				type = new BerTime();
				type.decode(this, false);
				break;
			case BerTag.TIME_OF_DAY_TAG:
				type = new BerTimeOfDay();
				type.decode(this, false);
				break;
			case BerTag.UTC_TIME_TAG:
				type = new BerUtcTime();
				type.decode(this, false);
				break;
			default:
				throw new UnsupportedOperationException(String.format("Unknown tag %d (%x)", tag.tagNumber, tag.tagNumber));
			}
			break;
		default:
			throw new UnsupportedOperationException(String.format("Unknown tag %d (%x)", tag.tagClass, tag.tagClass));
		}
		return type;
	}
}
